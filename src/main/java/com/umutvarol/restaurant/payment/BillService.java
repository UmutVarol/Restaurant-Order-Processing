package com.umutvarol.restaurant.payment;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.common.money.Money;
import com.umutvarol.restaurant.order.Order;
import com.umutvarol.restaurant.order.OrderChangedEvent;
import com.umutvarol.restaurant.order.OrderRepository;
import com.umutvarol.restaurant.order.OrderStatus;
import com.umutvarol.restaurant.order.OrderStatusChange;
import com.umutvarol.restaurant.order.OrderType;
import com.umutvarol.restaurant.order.PaymentMethod;
import com.umutvarol.restaurant.order.SettlementPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps every order on a bill and decides when an order may be completed. Dine-in orders join the open bill of
 * their table, so a table that orders in several rounds pays once; takeaway and delivery orders get a bill each.
 * <p>
 * Order changes arrive as events inside the order's transaction, so a bill is never out of step with its orders.
 */
@Service
@Transactional
public class BillService implements SettlementPolicy {

    private static final BigDecimal CASH_NOTE = new BigDecimal("50");

    private final BillRepository bills;
    private final OrderRepository orders;
    private final ApplicationEventPublisher events;
    private final PaymentAuditLog audit;
    private final Clock clock;

    public BillService(BillRepository bills, OrderRepository orders, ApplicationEventPublisher events,
                       PaymentAuditLog audit, Clock clock) {
        this.bills = bills;
        this.orders = orders;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public void requireSettled(Order order) {
        Bill bill = billFor(order);
        if (!bill.isSettled()) {
            throw new BusinessRuleException("BILL_NOT_PAID",
                    "Bill #%d still has %s to pay".formatted(bill.getId(), bill.balance()),
                    Map.of("billId", bill.getId(), "balance", bill.balance()));
        }
    }

    /** The bill an order is on, opening one if it has none yet. */
    public Bill billFor(Order order) {
        return bills.findByOrderId(order.getId()).orElseGet(() -> attach(order, clock.instant()));
    }

    Bill billForOrder(Long orderId) {
        return billFor(orders.findById(orderId).orElseThrow(() -> new ResourceNotFoundException("order", orderId)));
    }

    @EventListener
    void onOrderChanged(OrderChangedEvent event) {
        Order order = orders.findById(event.orderId()).orElseThrow();
        if (event.kind() == OrderChangedEvent.Kind.CREATED) {
            billFor(order);
        } else if (event.status() == OrderStatus.CANCELLED) {
            Bill bill = lock(billFor(order));
            Instant now = clock.instant();
            List<Refund> refunds = bill.refundOverpayment("Order #%d was cancelled".formatted(order.getId()), now,
                    lastActor(order));
            announceRefunds(refunds, now);
            refresh(bill, now);
        } else if (event.status() == OrderStatus.COMPLETED) {
            refresh(lock(billFor(order)), clock.instant());
        }
    }

    /** Closes the bill if nothing is left to do, and tells open screens. */
    void refresh(Bill bill, Instant at) {
        if (bill.refresh(at)) {
            events.publishEvent(PaymentChangedEvent.bill(bill, at));
        }
    }

    void announceRefunds(List<Refund> refunds, Instant at) {
        if (refunds.isEmpty()) {
            return;
        }
        bills.flush(); // assigns the refunds their ids
        for (Refund refund : refunds) {
            audit.refund(refund, refund.getCreatedBy());
            events.publishEvent(PaymentChangedEvent.refund(refund, at));
            if (refund.getStatus() == RefundStatus.PENDING) {
                events.publishEvent(new OnlineRefundRequested(refund.getId()));
            }
        }
    }

    /** Re-reads the bill with a row lock, so two tills working on the same bill take turns. */
    Bill lock(Bill bill) {
        return bills.findForUpdate(bill.getId()).orElseThrow();
    }

    Bill lock(Long billId) {
        return bills.findForUpdate(billId).orElseThrow(() -> new ResourceNotFoundException("bill", billId));
    }

    /**
     * Records how an order that happened before payments were tracked was paid: a bill and, for a completed order,
     * one payment of the order's total by its chosen method. Used to give demo history realistic takings.
     *
     * @param tip what the guest left on top; ignored unless the order was completed
     */
    public void recordHistory(Order order, BigDecimal tip) {
        if (order.getStatus().isActive()) {
            attach(order, order.getCreatedAt());
            return;
        }
        Instant at = order.statusSince();
        Bill bill = bills.save(Bill.openFor(order, order.getCreatedAt()));
        if (order.getStatus() == OrderStatus.COMPLETED) {
            BigDecimal amount = order.getPricing().total();
            String actor = lastActor(order);
            Payment payment = switch (order.getPaymentMethod()) {
                case CASH -> bill.takeCash(amount, tip, roundUpToNote(amount.add(tip)), at, actor, null);
                case CARD -> bill.takeCard(amount, tip, approvalCodeFor(order), at, actor, null);
                case ONLINE -> bill.startOnline(amount, tip, null, at, actor);
            };
            if (payment.getMethod() == PaymentMethod.ONLINE) {
                payment.startedOnline("SIMULATED", null);
                payment.succeed("demo-" + order.getId(), at);
            }
        }
        bill.refresh(at);
    }

    private Bill attach(Order order, Instant at) {
        if (order.getType() == OrderType.DINE_IN && order.getTable() != null && order.getStatus().isActive()) {
            Bill open = bills.findFirstByTableIdAndStatusOrderByOpenedAtDesc(order.getTable().getId(), BillStatus.OPEN)
                    .filter(Bill::hasActiveOrders)
                    .orElse(null);
            if (open != null) {
                open.addOrder(order);
                return open;
            }
        }
        return bills.save(Bill.openFor(order, at));
    }

    private static String lastActor(Order order) {
        List<OrderStatusChange> history = order.getHistory();
        return history.isEmpty() ? order.getCreatedBy() : history.getLast().getChangedBy();
    }

    private static BigDecimal roundUpToNote(BigDecimal amount) {
        return Money.normalize(amount.divide(CASH_NOTE, 0, RoundingMode.CEILING).multiply(CASH_NOTE));
    }

    private static String approvalCodeFor(Order order) {
        return "%06d".formatted(Math.floorMod(order.getId() * 7_919L, 1_000_000L));
    }
}
