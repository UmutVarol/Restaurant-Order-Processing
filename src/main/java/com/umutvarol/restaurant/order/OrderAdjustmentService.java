package com.umutvarol.restaurant.order;

import com.umutvarol.restaurant.common.error.AccessRefusedException;
import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.coupon.Coupon;
import com.umutvarol.restaurant.coupon.CouponRepository;
import com.umutvarol.restaurant.order.dto.DiscountRequest;
import com.umutvarol.restaurant.order.dto.LineAdjustmentRequest;
import com.umutvarol.restaurant.order.dto.ManagerApproval;
import com.umutvarol.restaurant.order.dto.OrderResponse;
import com.umutvarol.restaurant.order.pricing.ManualDiscount;
import com.umutvarol.restaurant.order.pricing.PricingCalculator;
import com.umutvarol.restaurant.security.StaffPinService;
import java.time.Clock;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changes to an order after it was placed that make it cheaper: voiding units, serving them on the house, and a
 * manual discount. A waiter needs a manager's approval (their PIN, entered at the till) for each of them, except
 * voiding while the kitchen has not started; a manager approves their own. Each change is recorded on the order,
 * logged to {@code order.audit}, repriced like a new order, and refused if the bill has already been paid beyond
 * the new total.
 */
@Service
@Transactional
public class OrderAdjustmentService {

    private static final Logger audit = LoggerFactory.getLogger("order.audit");

    private final OrderRepository orders;
    private final CouponRepository coupons;
    private final PricingCalculator pricing;
    private final SettlementPolicy settlement;
    private final StaffPinService approvals;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public OrderAdjustmentService(OrderRepository orders, CouponRepository coupons, PricingCalculator pricing,
                                  SettlementPolicy settlement, StaffPinService approvals,
                                  ApplicationEventPublisher events, Clock clock) {
        this.orders = orders;
        this.coupons = coupons;
        this.pricing = pricing;
        this.settlement = settlement;
        this.approvals = approvals;
        this.events = events;
        this.clock = clock;
    }

    public OrderResponse adjustLine(Long orderId, Long lineId, LineAdjustmentRequest request, Actor actor) {
        Order order = find(orderId);
        Instant now = clock.instant();
        String reason = request.reason().strip();
        OrderAdjustment adjustment = switch (request.kind()) {
            case VOID -> {
                // before the kitchen starts, a void is just correcting the ticket
                boolean needsApproval = order.getStatus() != OrderStatus.PENDING;
                OrderAdjustment voided = order.voidUnits(lineId, request.quantity(), reason, now, actor.username(),
                        approvedBy(actor, request.approval(), needsApproval));
                boolean fired = order.getLines().stream().anyMatch(line -> line.getId().equals(lineId) && line.isFired());
                if (fired) {
                    events.publishEvent(new LineVoidedEvent(order.getId(), lineId, request.quantity()));
                }
                yield voided;
            }
            case COMP -> order.compUnits(lineId, request.quantity(), reason, now, actor.username(),
                    approvedBy(actor, request.approval(), true));
            default -> throw new BusinessRuleException("ADJUSTMENT_KIND_INVALID", "Only VOID and COMP apply to a line");
        };
        return finish(order, adjustment, now);
    }

    public OrderResponse discount(Long orderId, DiscountRequest request, Actor actor) {
        if (request.percent() != null && request.amount() != null) {
            throw new BusinessRuleException("DISCOUNT_INVALID", "Give a percentage or an amount, not both");
        }
        Order order = find(orderId);
        Instant now = clock.instant();
        ManualDiscount discount = request.percent() == null && request.amount() == null ? null
                : new ManualDiscount(request.percent(), request.amount());
        OrderAdjustment adjustment = order.setManualDiscount(discount, request.reason().strip(), now, actor.username(),
                approvedBy(actor, request.approval(), true));
        return finish(order, adjustment, now);
    }

    private OrderResponse finish(Order order, OrderAdjustment adjustment, Instant now) {
        Coupon coupon = order.getCouponCode() == null ? null : coupons.findByCode(order.getCouponCode()).orElse(null);
        order.reprice(pricing.calculate(order.linesSubtotal(), order.getType(), coupon, order.manualDiscount()), now);
        settlement.requireCovered(order);
        audit.info("order.{} order={} item={} quantity={} amount={} by={} approvedBy={} detail=\"{}\"",
                adjustment.getKind().name().toLowerCase(), order.getId(), clean(adjustment.getItemName()),
                adjustment.getQuantity(), adjustment.getAmount(), adjustment.getActor(), adjustment.getApprovedBy(),
                clean(adjustment.getDetail()));
        events.publishEvent(OrderChangedEvent.updated(order));
        return OrderResponse.from(order);
    }

    private @Nullable String approvedBy(Actor actor, @Nullable ManagerApproval approval, boolean needed) {
        if (actor.manager()) {
            return actor.username();
        }
        if (!needed) {
            return null;
        }
        if (approval == null) {
            throw new AccessRefusedException("APPROVAL_REQUIRED", "A manager needs to approve this.");
        }
        return approvals.approveAsManager(approval.manager(), approval.pin(), actor.username(), actor.client());
    }

    private Order find(Long id) {
        return orders.findById(id).orElseThrow(() -> new ResourceNotFoundException("order", id));
    }

    private static String clean(@Nullable String value) {
        return value == null ? "-" : value.replaceAll("\\p{Cntrl}", "_");
    }

    /** Who is asking, and whether they are a manager. */
    public record Actor(String username, boolean manager, String client) {
    }
}
