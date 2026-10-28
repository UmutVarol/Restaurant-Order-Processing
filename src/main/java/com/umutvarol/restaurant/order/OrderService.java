package com.umutvarol.restaurant.order;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.common.money.Money;
import com.umutvarol.restaurant.common.web.PageResponse;
import com.umutvarol.restaurant.coupon.Coupon;
import com.umutvarol.restaurant.coupon.CouponService;
import com.umutvarol.restaurant.coupon.CouponValidation;
import com.umutvarol.restaurant.menu.MenuItem;
import com.umutvarol.restaurant.menu.MenuItemRepository;
import com.umutvarol.restaurant.order.dto.OrderLineRequest;
import com.umutvarol.restaurant.order.dto.OrderLineResponse;
import com.umutvarol.restaurant.order.dto.OrderResponse;
import com.umutvarol.restaurant.order.dto.OrderSearchCriteria;
import com.umutvarol.restaurant.order.dto.PlaceOrderRequest;
import com.umutvarol.restaurant.order.dto.QuoteRequest;
import com.umutvarol.restaurant.order.dto.QuoteResponse;
import com.umutvarol.restaurant.order.dto.QuoteResponse.CouponResult;
import com.umutvarol.restaurant.order.pricing.PriceBreakdown;
import com.umutvarol.restaurant.order.pricing.PricingCalculator;
import com.umutvarol.restaurant.table.DiningTable;
import com.umutvarol.restaurant.table.DiningTableRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for the order lifecycle: pricing carts, placing orders and driving them through the
 * kitchen. Business rules live in the {@link Order} aggregate; this class orchestrates repositories,
 * pricing and event publication.
 */
@Service
@Transactional
public class OrderService {

    private final OrderRepository orders;
    private final MenuItemRepository menuItems;
    private final DiningTableRepository tables;
    private final CouponService coupons;
    private final PricingCalculator pricing;
    private final ApplicationEventPublisher events;
    private final SettlementPolicy settlement;
    private final Clock clock;

    public OrderService(OrderRepository orders, MenuItemRepository menuItems, DiningTableRepository tables,
                        CouponService coupons, PricingCalculator pricing, ApplicationEventPublisher events,
                        SettlementPolicy settlement, Clock clock) {
        this.orders = orders;
        this.menuItems = menuItems;
        this.tables = tables;
        this.coupons = coupons;
        this.pricing = pricing;
        this.events = events;
        this.settlement = settlement;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public QuoteResponse quote(QuoteRequest request) {
        List<OrderLine> lines = buildLines(request.items());
        BigDecimal subtotal = subtotalOf(lines);

        Coupon coupon = null;
        CouponResult couponResult = null;
        if (!isBlank(request.couponCode())) {
            CouponValidation validation = coupons.validate(request.couponCode(), subtotal);
            coupon = validation.coupon();
            couponResult = new CouponResult(validation.code(), validation.isValid(),
                    coupon == null ? null : coupon.getDescription(), validation.errorCode(), validation.message());
        }

        PriceBreakdown breakdown = pricing.calculate(subtotal, request.type(), coupon);
        return new QuoteResponse(
                lines.stream().map(OrderLineResponse::from).toList(),
                breakdown,
                couponResult,
                pricing.freeDeliveryThreshold(),
                pricing.amountUntilFreeDelivery(breakdown, request.type()));
    }

    public OrderResponse placeOrder(PlaceOrderRequest request, String actor) {
        List<OrderLine> lines = buildLines(request.items());
        BigDecimal subtotal = subtotalOf(lines);
        Coupon coupon = isBlank(request.couponCode())
                ? null
                : coupons.validate(request.couponCode(), subtotal).orElseThrow();
        DiningTable table = request.type() == OrderType.DINE_IN ? findTable(request.tableId()) : null;

        OrderDetails details = new OrderDetails(request.type(), request.customerName(), request.customerPhone(), table,
                request.deliveryAddress(), request.note(), request.paymentMethod(),
                coupon == null ? null : coupon.getCode());
        PriceBreakdown breakdown = pricing.calculate(subtotal, request.type(), coupon);

        Order placed = Order.place(details, lines, breakdown, clock.instant(), actor);
        if (Boolean.TRUE.equals(request.holdCourses())) {
            placed.holdLaterCourses();
        }
        Order order = orders.save(placed);
        events.publishEvent(OrderChangedEvent.created(order));
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> search(OrderSearchCriteria criteria, Pageable pageable) {
        Specification<Order> specification = Specification.allOf(
                OrderSpecifications.hasStatusIn(criteria.statuses()),
                OrderSpecifications.hasType(criteria.type()),
                OrderSpecifications.matchesText(criteria.query()),
                OrderSpecifications.createdBetween(startOfDay(criteria.from()),
                        criteria.to() == null ? null : startOfDay(criteria.to().plusDays(1))));
        return PageResponse.from(orders.findAll(specification, pageable), OrderResponse::from);
    }

    /** Orders the kitchen and floor staff still need to act on, oldest first. */
    @Transactional(readOnly = true)
    public List<OrderResponse> activeOrders() {
        return orders.findByStatusInOrderByCreatedAtAsc(OrderStatus.activeStatuses()).stream()
                .map(OrderResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderResponse get(Long id) {
        return OrderResponse.from(find(id));
    }

    /**
     * Moves an order to its next status. Completing an order (served and paid, picked up, delivered) requires its
     * bill to be paid in full.
     */
    public OrderResponse advance(Long id, String actor) {
        Order order = find(id);
        OrderStatus previous = order.getStatus();
        if (order.nextStatus().filter(next -> next == OrderStatus.COMPLETED).isPresent()) {
            settlement.requireSettled(order);
        }
        order.advance(clock.instant(), actor);
        events.publishEvent(OrderChangedEvent.statusChanged(order, previous));
        return OrderResponse.from(order);
    }

    public OrderResponse cancel(Long id, String reason, String actor) {
        Order order = find(id);
        OrderStatus previous = order.getStatus();
        order.cancel(reason, clock.instant(), actor);
        events.publishEvent(OrderChangedEvent.statusChanged(order, previous));
        return OrderResponse.from(order);
    }

    /** Sends a held course to the kitchen. */
    public OrderResponse fireCourse(Long id, int course, String actor) {
        Order order = find(id);
        order.fireCourse(course, clock.instant(), actor);
        events.publishEvent(OrderChangedEvent.updated(order));
        events.publishEvent(new CourseFiredEvent(order.getId(), course));
        return OrderResponse.from(order);
    }

    /** A cook ticks a dish off at their station (or un-ticks it). */
    public OrderResponse markLineReady(Long id, Long lineId, boolean ready) {
        Order order = find(id);
        order.markLineReady(lineId, ready, clock.instant());
        events.publishEvent(OrderChangedEvent.updated(order));
        return OrderResponse.from(order);
    }

    private Order find(Long id) {
        return orders.findById(id).orElseThrow(() -> new ResourceNotFoundException("order", id));
    }

    private DiningTable findTable(Long tableId) {
        return tables.findById(tableId).orElseThrow(() -> new BusinessRuleException("TABLE_NOT_FOUND",
                "Table %d does not exist".formatted(tableId), Map.of("tableId", tableId)));
    }

    private List<OrderLine> buildLines(List<OrderLineRequest> items) {
        Map<Long, MenuItem> menu = menuItems.findAllById(items.stream().map(OrderLineRequest::menuItemId).toList())
                .stream()
                .collect(Collectors.toMap(MenuItem::getId, Function.identity()));
        return items.stream().map(item -> {
            MenuItem menuItem = menu.get(item.menuItemId());
            if (menuItem == null || menuItem.isArchived()) {
                throw new BusinessRuleException("MENU_ITEM_NOT_FOUND",
                        "Menu item %d does not exist".formatted(item.menuItemId()),
                        Map.of("menuItemId", item.menuItemId()));
            }
            if (!menuItem.isOrderable()) {
                throw new BusinessRuleException("MENU_ITEM_UNAVAILABLE",
                        "%s is sold out".formatted(menuItem.getName()),
                        Map.of("menuItemId", menuItem.getId(), "name", menuItem.getName()));
            }
            return OrderLine.of(menuItem, item.quantity(), item.note(), menuItem.resolveOptions(item.optionIds()))
                    .inCourse(item.course());
        }).toList();
    }

    private static BigDecimal subtotalOf(List<OrderLine> lines) {
        return lines.stream().map(OrderLine::getLineTotal).reduce(Money.ZERO, BigDecimal::add);
    }

    private @Nullable Instant startOfDay(@Nullable LocalDate date) {
        return date == null ? null : date.atStartOfDay(clock.getZone()).toInstant();
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
