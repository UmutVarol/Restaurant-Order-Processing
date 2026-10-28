package com.umutvarol.restaurant.payment;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.InvalidStateException;
import com.umutvarol.restaurant.common.money.Money;
import com.umutvarol.restaurant.order.AdjustmentKind;
import com.umutvarol.restaurant.order.Order;
import com.umutvarol.restaurant.order.OrderStatus;
import com.umutvarol.restaurant.order.PaymentMethod;
import com.umutvarol.restaurant.table.DiningTable;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Aggregate root for what a guest settles: every order of one dine-in seating, or one takeaway or delivery order.
 * All money rules live here: a payment can never take more than is still due, a tip can never be larger than
 * the payment, cash must cover the payment, and money paid for orders that are later cancelled is refunded.
 *
 * <pre>
 * total     = Σ totals of orders that are not cancelled
 * paid      = Σ successful payments − refunds
 * balance   = total − paid                (what is still due; negative means overpaid)
 * available = balance − pending online payments  (what may still be charged)
 * </pre>
 */
@Entity
@Table(name = "bills")
public class Bill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BillStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "table_id")
    private DiningTable table;

    @ManyToMany
    @JoinTable(name = "bill_orders", joinColumns = @JoinColumn(name = "bill_id"),
            inverseJoinColumns = @JoinColumn(name = "order_id"))
    @OrderBy("id ASC")
    private List<Order> orders = new ArrayList<>();

    @OneToMany(mappedBy = "bill", cascade = CascadeType.ALL)
    @OrderBy("id ASC")
    private List<Payment> payments = new ArrayList<>();

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    private long version;

    protected Bill() {
        // for JPA
    }

    /** Opens a bill for an order; a dine-in bill is tied to the order's table so later rounds join it. */
    static Bill openFor(Order order, Instant at) {
        Bill bill = new Bill();
        bill.status = BillStatus.OPEN;
        bill.table = order.getTable();
        bill.openedAt = at;
        bill.orders.add(order);
        return bill;
    }

    void addOrder(Order order) {
        if (!orders.contains(order)) {
            orders.add(order);
        }
        if (status != BillStatus.OPEN) {
            status = BillStatus.OPEN;
            closedAt = null;
        }
    }

    /** The seating moves to another table, with every order on it. */
    void moveTo(DiningTable target, Instant at, String actor) {
        requireOpen();
        this.table = target;
        orders.forEach(order -> order.moveTo(target, AdjustmentKind.MOVE, at, actor));
    }

    /**
     * Takes over every order of another table's bill. The other bill must not have taken any money, so no payment
     * or QR link ever ends up pointing at orders it was not for; it becomes void.
     */
    void absorb(Bill source, Instant at, String actor) {
        requireOpen();
        source.requireOpen();
        if (source.paid().signum() != 0 || source.pending().signum() != 0 || source.refunded().signum() != 0) {
            throw new BusinessRuleException("MERGE_SOURCE_HAS_PAYMENTS",
                    "Bill #%d already has payments; settle it on its own".formatted(source.id),
                    Map.of("billId", String.valueOf(source.id)));
        }
        for (Order order : List.copyOf(source.orders)) {
            order.moveTo(table, AdjustmentKind.MERGE, at, actor);
            orders.add(order);
        }
        source.orders.clear();
        source.status = BillStatus.VOID;
        source.closedAt = at;
    }

    private void requireOpen() {
        if (status != BillStatus.OPEN) {
            throw new InvalidStateException("BILL_NOT_OPEN", "Bill #%d is %s".formatted(id, status),
                    Map.of("billId", String.valueOf(id), "status", status));
        }
    }

    // ------------------------------------------------------------------------------------------- taking money

    Payment takeCash(BigDecimal amount, BigDecimal tip, BigDecimal tendered, Instant at, String actor,
                     @Nullable String idempotencyKey) {
        BigDecimal value = Money.normalize(amount);
        BigDecimal tipValue = Money.normalize(tip);
        checkPayable(value, tipValue);
        if (Money.normalize(tendered).compareTo(value.add(tipValue)) < 0) {
            throw new BusinessRuleException("CASH_NOT_ENOUGH", "The cash handed over does not cover the payment",
                    Map.of("required", value.add(tipValue)));
        }
        Payment payment = new Payment(this, PaymentMethod.CASH, PaymentStatus.SUCCEEDED, value, tipValue, at, actor,
                idempotencyKey);
        payment.recordCash(tendered);
        payments.add(payment);
        return payment;
    }

    Payment takeCard(BigDecimal amount, BigDecimal tip, @Nullable String approvalCode, Instant at, String actor,
                     @Nullable String idempotencyKey) {
        BigDecimal value = Money.normalize(amount);
        BigDecimal tipValue = Money.normalize(tip);
        checkPayable(value, tipValue);
        Payment payment = new Payment(this, PaymentMethod.CARD, PaymentStatus.SUCCEEDED, value, tipValue, at, actor,
                idempotencyKey);
        payment.recordApprovalCode(approvalCode);
        payments.add(payment);
        return payment;
    }

    /** Reserves an amount for an online payment that the guest is about to make on the provider's page. */
    Payment startOnline(BigDecimal amount, BigDecimal tip, @Nullable PaymentLink link, Instant at, String actor) {
        BigDecimal value = Money.normalize(amount);
        BigDecimal tipValue = Money.normalize(tip);
        checkPayable(value, tipValue);
        Payment payment = new Payment(this, PaymentMethod.ONLINE, PaymentStatus.PENDING, value, tipValue, at, actor, null);
        payment.viaLink(link);
        payments.add(payment);
        return payment;
    }

    private void checkPayable(BigDecimal amount, BigDecimal tip) {
        if (status != BillStatus.OPEN) {
            throw new InvalidStateException("BILL_NOT_OPEN", "Bill #%d is %s".formatted(id, status),
                    Map.of("billId", String.valueOf(id), "status", status));
        }
        if (amount.signum() <= 0) {
            throw new BusinessRuleException("PAYMENT_AMOUNT_INVALID", "A payment must be more than zero");
        }
        if (amount.compareTo(available()) > 0) {
            throw new BusinessRuleException("PAYMENT_EXCEEDS_BALANCE",
                    "Only %s is still due on bill #%d".formatted(available(), id), Map.of("available", available()));
        }
        if (tip.signum() < 0 || tip.compareTo(amount) > 0) {
            throw new BusinessRuleException("TIP_INVALID", "A tip cannot be negative or larger than the payment");
        }
    }

    // ------------------------------------------------------------------------------------------- refunds

    /**
     * Gives back whatever has been paid beyond the total, newest payments first: after an order is cancelled, or
     * when a late online payment arrives after the bill was already settled. Cash and card refunds are recorded
     * as done (the money is handed back at the till); online refunds stay pending until the provider confirms.
     */
    List<Refund> refundOverpayment(String reason, Instant at, String actor) {
        BigDecimal excess = paid().subtract(total());
        List<Refund> refunds = new ArrayList<>();
        List<Payment> newestFirst = payments.stream()
                .filter(payment -> payment.refundable().signum() > 0)
                .sorted(Comparator.comparing(Payment::getCreatedAt).thenComparing(Payment::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
        for (Payment payment : newestFirst) {
            if (excess.signum() <= 0) {
                break;
            }
            BigDecimal portion = excess.min(payment.refundable());
            refunds.add(payment.refund(portion, initialRefundStatus(payment), reason, at, actor));
            excess = excess.subtract(portion);
        }
        return refunds;
    }

    /** A refund a manager gives on purpose, e.g. after a complaint. The bill then shows the amount as unpaid. */
    Refund refund(Payment payment, BigDecimal amount, String reason, Instant at, String actor) {
        BigDecimal value = Money.normalize(amount);
        if (!payments.contains(payment)) {
            throw new IllegalArgumentException("Payment does not belong to bill " + id);
        }
        if (value.signum() <= 0 || value.compareTo(payment.refundable()) > 0) {
            throw new BusinessRuleException("REFUND_EXCEEDS_PAYMENT",
                    "At most %s can be refunded from this payment".formatted(payment.refundable()),
                    Map.of("refundable", payment.refundable()));
        }
        return payment.refund(value, initialRefundStatus(payment), reason, at, actor);
    }

    private static RefundStatus initialRefundStatus(Payment payment) {
        return payment.getMethod() == PaymentMethod.ONLINE ? RefundStatus.PENDING : RefundStatus.SUCCEEDED;
    }

    // ------------------------------------------------------------------------------------------- lifecycle

    /**
     * Closes the bill once nothing is left to do: every order is finished and nothing is due. A bill whose orders
     * were all cancelled, with nothing kept, becomes void.
     *
     * @return whether the bill changed status
     */
    boolean refresh(Instant at) {
        if (status != BillStatus.OPEN || hasActiveOrders()) {
            return false;
        }
        if (balance().signum() > 0 || payments.stream().anyMatch(Payment::isPending)) {
            return false;
        }
        boolean allCancelled = orders.stream().allMatch(order -> order.getStatus() == OrderStatus.CANCELLED);
        status = allCancelled && paid().signum() == 0 ? BillStatus.VOID : BillStatus.CLOSED;
        closedAt = at;
        return true;
    }

    /** Whether any order on the bill is still being prepared, served or delivered. */
    public boolean hasActiveOrders() {
        return orders.stream().anyMatch(order -> order.getStatus().isActive());
    }

    public BigDecimal total() {
        return orders.stream()
                .filter(order -> order.getStatus() != OrderStatus.CANCELLED)
                .map(order -> order.getPricing().total())
                .reduce(Money.ZERO, BigDecimal::add);
    }

    public BigDecimal paid() {
        return payments.stream().map(Payment::settledAmount).reduce(Money.ZERO, BigDecimal::add);
    }

    public BigDecimal pending() {
        return payments.stream().filter(Payment::isPending).map(Payment::getAmount).reduce(Money.ZERO, BigDecimal::add);
    }

    /** Money given back, or being given back, to the guest. */
    public BigDecimal refunded() {
        return payments.stream().map(Payment::getRefundedAmount).reduce(Money.ZERO, BigDecimal::add);
    }

    public BigDecimal tips() {
        return payments.stream()
                .filter(payment -> payment.getStatus() == PaymentStatus.SUCCEEDED)
                .map(Payment::getTip)
                .reduce(Money.ZERO, BigDecimal::add);
    }

    /** What is still due. Negative when more was paid than the orders are worth. */
    public BigDecimal balance() {
        return total().subtract(paid());
    }

    /** What may still be charged: the balance minus amounts held by online payments in progress. */
    public BigDecimal available() {
        return balance().subtract(pending()).max(Money.ZERO);
    }

    public boolean isSettled() {
        return balance().signum() <= 0;
    }

    public Long getId() {
        return id;
    }

    public BillStatus getStatus() {
        return status;
    }

    public DiningTable getTable() {
        return table;
    }

    public List<Order> getOrders() {
        return Collections.unmodifiableList(orders);
    }

    public List<Payment> getPayments() {
        return Collections.unmodifiableList(payments);
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
