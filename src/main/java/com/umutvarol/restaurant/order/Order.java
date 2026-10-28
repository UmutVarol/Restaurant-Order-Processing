package com.umutvarol.restaurant.order;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.InvalidStateException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.common.money.Money;
import com.umutvarol.restaurant.order.pricing.ManualDiscount;
import com.umutvarol.restaurant.order.pricing.PriceBreakdown;
import com.umutvarol.restaurant.table.DiningTable;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Aggregate root for a guest's order. All state changes go through intention-revealing methods
 * ({@link #advance}, {@link #cancel}) that enforce the {@link OrderStatus} state machine and record an
 * audit trail, rather than through setters.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "customer_name", nullable = false, length = 80)
    private String customerName;

    @Column(name = "customer_phone", length = 20)
    private String customerPhone;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "table_id")
    private DiningTable table;

    @Column(name = "delivery_address", length = 255)
    private String deliveryAddress;

    @Column(length = 255)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;

    @Column(name = "coupon_code", length = 32)
    private String couponCode;

    @Embedded
    private PriceBreakdown pricing;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", length = 50)
    private String createdBy;

    @Column(name = "cancellation_reason", length = 255)
    private String cancellationReason;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<OrderLine> lines = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("changedAt ASC, id ASC")
    private List<OrderStatusChange> history = new ArrayList<>();

    @Version
    private long version;

    @Column(name = "manual_discount_percent", precision = 5, scale = 2)
    private BigDecimal manualDiscountPercent;

    @Column(name = "manual_discount_amount", precision = 12, scale = 2)
    private BigDecimal manualDiscountAmount;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC, id ASC")
    private List<OrderAdjustment> adjustments = new ArrayList<>();

    protected Order() {
        // for JPA
    }

    /**
     * Places a new order in {@link OrderStatus#PENDING}.
     *
     * @throws BusinessRuleException if the order has no lines, a dine-in order has no table, or a delivery
     *                               order has no address
     */
    public static Order place(OrderDetails details, List<OrderLine> lines, PriceBreakdown pricing,
                              Instant placedAt, String placedBy) {
        Objects.requireNonNull(details, "details");
        if (lines == null || lines.isEmpty()) {
            throw new BusinessRuleException("ORDER_EMPTY", "An order needs at least one item");
        }
        if (details.type() == OrderType.DINE_IN && details.table() == null) {
            throw new BusinessRuleException("TABLE_REQUIRED", "Dine-in orders must be assigned to a table");
        }
        if (details.type() == OrderType.DELIVERY && isBlank(details.deliveryAddress())) {
            throw new BusinessRuleException("ADDRESS_REQUIRED", "Delivery orders need a delivery address");
        }

        Order order = new Order();
        order.type = details.type();
        order.status = OrderStatus.PENDING;
        order.customerName = details.customerName().strip();
        order.customerPhone = blankToNull(details.customerPhone());
        order.table = details.type() == OrderType.DINE_IN ? details.table() : null;
        order.deliveryAddress = details.type() == OrderType.DELIVERY ? details.deliveryAddress().strip() : null;
        order.note = blankToNull(details.note());
        order.paymentMethod = Objects.requireNonNull(details.paymentMethod(), "paymentMethod");
        order.couponCode = blankToNull(details.couponCode());
        order.pricing = Objects.requireNonNull(pricing, "pricing");
        order.createdAt = placedAt;
        order.updatedAt = placedAt;
        order.createdBy = placedBy;
        lines.forEach(order::addLine);
        order.history.add(new OrderStatusChange(order, OrderStatus.PENDING, placedAt, placedBy));
        return order;
    }

    /**
     * Moves the order to the next step of its lifecycle.
     *
     * @return the new status
     * @throws InvalidStateException if the order is already completed or cancelled
     */
    public OrderStatus advance(Instant at, String actor) {
        OrderStatus next = status.next(type).orElseThrow(() -> new InvalidStateException("ORDER_NOT_ADVANCEABLE",
                "Order #%d is %s and cannot be advanced".formatted(id, status),
                Map.of("orderId", String.valueOf(id), "status", status)));
        transitionTo(next, at, actor);
        return next;
    }

    /**
     * Cancels the order.
     *
     * @throws InvalidStateException if the kitchen has already finished the order
     */
    public void cancel(String reason, Instant at, String actor) {
        if (!status.isCancellable()) {
            throw new InvalidStateException("ORDER_NOT_CANCELLABLE",
                    "Order #%d is %s and can no longer be cancelled".formatted(id, status),
                    Map.of("orderId", String.valueOf(id), "status", status));
        }
        this.cancellationReason = blankToNull(reason);
        transitionTo(OrderStatus.CANCELLED, at, actor);
    }

    // ------------------------------------------------------------------------------------------- adjustments

    /**
     * Takes units off a line. The line stays on the order (and the kitchen ticket) with the voided units shown.
     *
     * @throws InvalidStateException  if the order is finished
     * @throws BusinessRuleException  if more units are voided than are left, or the order would be left empty
     */
    public OrderAdjustment voidUnits(Long lineId, int units, String reason, Instant at, String actor,
                                     @Nullable String approvedBy) {
        requireEditable();
        OrderLine line = line(lineId);
        requireUnits(line, units);
        if (lines.stream().mapToInt(OrderLine::activeQuantity).sum() - units <= 0) {
            throw new BusinessRuleException("VOID_LAST_ITEM", "Cancel the order instead of voiding everything on it");
        }
        BigDecimal amount = Money.normalize(line.getUnitPrice().multiply(BigDecimal.valueOf(units)));
        line.voidUnits(units);
        return record(new OrderAdjustment(this, AdjustmentKind.VOID, line, units, amount, reason, actor, approvedBy, at));
    }

    /** Serves units on the house: they stay on the ticket, but are not charged. */
    public OrderAdjustment compUnits(Long lineId, int units, String reason, Instant at, String actor,
                                     @Nullable String approvedBy) {
        requireEditable();
        OrderLine line = line(lineId);
        requireUnits(line, units);
        BigDecimal amount = Money.normalize(line.getUnitPrice().multiply(BigDecimal.valueOf(units)));
        line.compUnits(units);
        return record(new OrderAdjustment(this, AdjustmentKind.COMP, line, units, amount, reason, actor, approvedBy, at));
    }

    /** Sets, replaces or (with null) removes the manual discount. The caller reprices the order afterwards. */
    public OrderAdjustment setManualDiscount(@Nullable ManualDiscount discount, String reason, Instant at, String actor,
                                             @Nullable String approvedBy) {
        requireEditable();
        manualDiscountPercent = discount == null ? null : discount.percent();
        manualDiscountAmount = discount == null ? null : discount.amount();
        String detail = discount == null ? "removed: " + reason
                : (discount.percent() != null ? "%" + discount.percent().stripTrailingZeros().toPlainString() : discount.amount().toPlainString())
                + ": " + reason;
        return record(new OrderAdjustment(this, AdjustmentKind.DISCOUNT, null, null,
                discount == null ? null : discount.amount(), truncate(detail), actor, approvedBy, at));
    }

    public @Nullable ManualDiscount manualDiscount() {
        return manualDiscountPercent == null && manualDiscountAmount == null ? null
                : new ManualDiscount(manualDiscountPercent, manualDiscountAmount);
    }

    /** Σ line totals after voids and comps: what the order is priced on. */
    public BigDecimal linesSubtotal() {
        return Money.normalize(lines.stream().map(OrderLine::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    public void reprice(PriceBreakdown pricing, Instant at) {
        this.pricing = Objects.requireNonNull(pricing, "pricing");
        this.updatedAt = at;
    }

    /** Moves a dine-in order to another table, with its seating (a move) or onto another seating's bill (a merge). */
    public void moveTo(DiningTable target, AdjustmentKind kind, Instant at, String actor) {
        if (type != OrderType.DINE_IN || kind != AdjustmentKind.MOVE && kind != AdjustmentKind.MERGE) {
            throw new IllegalArgumentException("Only dine-in orders move between tables");
        }
        String from = table == null ? "-" : table.getCode();
        this.table = target;
        this.updatedAt = at;
        record(new OrderAdjustment(this, kind, null, null, null, from + " → " + target.getCode(), actor, null, at));
    }

    public List<OrderAdjustment> getAdjustments() {
        return Collections.unmodifiableList(adjustments);
    }

    private OrderAdjustment record(OrderAdjustment adjustment) {
        adjustments.add(adjustment);
        return adjustment;
    }

    private void requireEditable() {
        if (!status.isActive()) {
            throw new InvalidStateException("ORDER_NOT_EDITABLE", "Order #%d is %s and can no longer be changed"
                    .formatted(id, status), Map.of("orderId", String.valueOf(id), "status", status));
        }
    }

    private OrderLine line(Long lineId) {
        return lines.stream().filter(line -> line.getId().equals(lineId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("order line", lineId));
    }

    private static void requireUnits(OrderLine line, int units) {
        if (units < 1 || units > line.billableQuantity()) {
            throw new BusinessRuleException("ADJUST_QUANTITY_INVALID",
                    "Only %d of %s can still be changed".formatted(line.billableQuantity(), line.getItemName()),
                    Map.of("max", line.billableQuantity(), "name", line.getItemName()));
        }
    }

    private static String truncate(String value) {
        return value.length() <= 255 ? value : value.substring(0, 254) + "…";
    }

    public Optional<OrderStatus> nextStatus() {
        return status.next(type);
    }

    public int itemCount() {
        return lines.stream().mapToInt(OrderLine::activeQuantity).sum();
    }

    /** When the order entered its current status. */
    public Instant statusSince() {
        return history.isEmpty() ? createdAt : history.getLast().getChangedAt();
    }

    private void transitionTo(OrderStatus next, Instant at, String actor) {
        this.status = next;
        this.updatedAt = at;
        this.history.add(new OrderStatusChange(this, next, at, actor));
    }

    private void addLine(OrderLine line) {
        line.attachTo(this);
        lines.add(line);
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return isBlank(value) ? null : value.strip();
    }

    public Long getId() {
        return id;
    }

    public OrderType getType() {
        return type;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getCustomerPhone() {
        return customerPhone;
    }

    public DiningTable getTable() {
        return table;
    }

    public String getDeliveryAddress() {
        return deliveryAddress;
    }

    public String getNote() {
        return note;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public String getCouponCode() {
        return couponCode;
    }

    public PriceBreakdown getPricing() {
        return pricing;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public List<OrderLine> getLines() {
        return Collections.unmodifiableList(lines);
    }

    public List<OrderStatusChange> getHistory() {
        return Collections.unmodifiableList(history);
    }

    public long getVersion() {
        return version;
    }
}
