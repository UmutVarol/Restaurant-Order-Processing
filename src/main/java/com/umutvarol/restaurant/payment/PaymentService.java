package com.umutvarol.restaurant.payment;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.common.money.Money;
import com.umutvarol.restaurant.order.Order;
import com.umutvarol.restaurant.order.OrderService;
import com.umutvarol.restaurant.order.OrderStatus;
import com.umutvarol.restaurant.order.OrderType;
import com.umutvarol.restaurant.payment.dto.BillResponse;
import com.umutvarol.restaurant.payment.dto.BillSummaryResponse;
import com.umutvarol.restaurant.payment.dto.PaymentResponse;
import com.umutvarol.restaurant.payment.dto.RefundRequest;
import com.umutvarol.restaurant.payment.dto.TakePaymentRequest;
import com.umutvarol.restaurant.payment.dto.TakePaymentResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bills and the money taken at the till: cash and card payments, refunds. Every change to a bill's money happens
 * under a row lock on the bill, so two waiters settling the same table at once cannot overcharge it.
 */
@Service
@Transactional
public class PaymentService {

    /** Client-chosen keys that make a retried "take payment" request safe (e.g. after a timeout). */
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_-]{8,80}");

    private final BillService billService;
    private final BillRepository bills;
    private final PaymentRepository payments;
    private final OrderService orderService;
    private final ApplicationEventPublisher events;
    private final PaymentAuditLog audit;
    private final Clock clock;

    public PaymentService(BillService billService, BillRepository bills, PaymentRepository payments,
                          OrderService orderService, ApplicationEventPublisher events, PaymentAuditLog audit, Clock clock) {
        this.billService = billService;
        this.bills = bills;
        this.payments = payments;
        this.orderService = orderService;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    public BillResponse get(Long billId) {
        return BillResponse.from(bills.findById(billId).orElseThrow(() -> new ResourceNotFoundException("bill", billId)));
    }

    /** The bill of an order. Not read-only: an order placed before payments were tracked gets its bill now. */
    public BillResponse forOrder(Long orderId) {
        return BillResponse.from(billService.billForOrder(orderId));
    }

    @Transactional(readOnly = true)
    public List<BillSummaryResponse> openBills() {
        return bills.findByStatus(BillStatus.OPEN).stream()
                .sorted(Comparator.comparing(Bill::getOpenedAt))
                .map(BillSummaryResponse::from)
                .toList();
    }

    /**
     * Takes a cash or card payment. With an idempotency key, sending the same request again returns the payment
     * that was already taken instead of charging twice.
     */
    public TakePaymentResponse take(Long billId, TakePaymentRequest request, @Nullable String idempotencyKey,
                                    String actor) {
        if (idempotencyKey != null && !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new BusinessRuleException("IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key must be 8 to 80 letters, digits, '-' or '_'");
        }
        Bill bill = billService.lock(billId);
        // Looked up after taking the lock, so a retry that raced the original sees its committed payment.
        Optional<Payment> earlier = idempotencyKey == null ? Optional.empty() : payments.findByIdempotencyKey(idempotencyKey);
        if (earlier.isPresent()) {
            if (!earlier.get().getBill().getId().equals(billId)) {
                throw new BusinessRuleException("IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used for another bill");
            }
            return new TakePaymentResponse(PaymentResponse.from(earlier.get()), BillResponse.from(bill));
        }

        Instant now = clock.instant();
        BigDecimal tip = request.tip() == null ? Money.ZERO : request.tip();
        Payment payment = switch (request.method()) {
            case CASH -> bill.takeCash(request.amount(), tip,
                    request.tendered() == null ? request.amount().add(tip) : request.tendered(), now, actor, idempotencyKey);
            case CARD -> bill.takeCard(request.amount(), tip, blankToNull(request.approvalCode()), now, actor,
                    idempotencyKey);
            case ONLINE -> throw new BusinessRuleException("PAYMENT_METHOD_INVALID",
                    "Online payments are taken through a payment link");
        };
        payments.saveAndFlush(payment);
        audit.paymentTaken(payment, actor);
        events.publishEvent(PaymentChangedEvent.payment(payment, now));
        afterPayment(bill, actor, now);
        return new TakePaymentResponse(PaymentResponse.from(payment), BillResponse.from(bill));
    }

    /** Gives money back from a payment, e.g. after a complaint. Only managers may do this. */
    public PaymentResponse refund(Long paymentId, RefundRequest request, String actor) {
        Payment found = payments.findById(paymentId).orElseThrow(() -> new ResourceNotFoundException("payment", paymentId));
        Bill bill = billService.lock(found.getBill());
        Payment payment = bill.getPayments().stream().filter(p -> p.getId().equals(paymentId)).findFirst().orElseThrow();
        if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
            throw new BusinessRuleException("PAYMENT_NOT_REFUNDABLE", "Only successful payments can be refunded",
                    Map.of("status", payment.getStatus()));
        }
        Instant now = clock.instant();
        Refund refund = bill.refund(payment, request.amount(), request.reason().strip(), now, actor);
        billService.announceRefunds(List.of(refund), now);
        return PaymentResponse.from(payment);
    }

    /**
     * Once a bill is paid in full, dine-in orders that have been served are complete: the guests have eaten and
     * paid. Takeaway and delivery orders still wait for the hand-over, which staff confirm themselves.
     */
    void afterPayment(Bill bill, String actor, Instant at) {
        if (bill.isSettled()) {
            List<Long> served = bill.getOrders().stream()
                    .filter(order -> order.getType() == OrderType.DINE_IN && order.getStatus() == OrderStatus.SERVED)
                    .map(Order::getId)
                    .toList();
            served.forEach(orderId -> orderService.advance(orderId, actor));
        }
        billService.refresh(bill, at);
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
