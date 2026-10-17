package com.umutvarol.restaurant.order;

import static com.umutvarol.restaurant.support.TestData.menuItem;
import static com.umutvarol.restaurant.support.TestData.table;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.coupon.Coupon;
import com.umutvarol.restaurant.coupon.CouponRepository;
import com.umutvarol.restaurant.coupon.CouponService;
import com.umutvarol.restaurant.coupon.DiscountType;
import com.umutvarol.restaurant.menu.MenuCategory;
import com.umutvarol.restaurant.menu.MenuItem;
import com.umutvarol.restaurant.menu.MenuItemRepository;
import com.umutvarol.restaurant.order.dto.OrderLineRequest;
import com.umutvarol.restaurant.order.dto.OrderResponse;
import com.umutvarol.restaurant.order.dto.PlaceOrderRequest;
import com.umutvarol.restaurant.order.dto.QuoteRequest;
import com.umutvarol.restaurant.order.dto.QuoteResponse;
import com.umutvarol.restaurant.order.pricing.PricingCalculator;
import com.umutvarol.restaurant.order.pricing.PricingProperties;
import com.umutvarol.restaurant.table.DiningTableRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-20T17:30:00Z");

    @Mock
    private OrderRepository orders;
    @Mock
    private MenuItemRepository menuItems;
    @Mock
    private DiningTableRepository tables;
    @Mock
    private CouponRepository couponRepository;
    @Mock
    private ApplicationEventPublisher events;

    private OrderService service;

    private final MenuItem kebap = menuItem(7, "Adana Kebap", MenuCategory.MAIN, "520.00");
    private final MenuItem tea = menuItem(21, "Demlik Çay", MenuCategory.DRINK, "45.00");

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneId.of("Europe/Istanbul"));
        PricingCalculator pricing = new PricingCalculator(
                new PricingProperties(new BigDecimal("0.10"), new BigDecimal("39.90"), new BigDecimal("750.00")));
        service = new OrderService(orders, menuItems, tables, new CouponService(couponRepository, clock), pricing,
                events, clock);
    }

    @Test
    void placesAPricedOrderAndAnnouncesIt() {
        when(menuItems.findAllById(anyIterable())).thenReturn(List.of(kebap, tea));
        when(tables.findById(4L)).thenReturn(Optional.of(table(4, "A4")));
        when(orders.save(any(Order.class))).thenAnswer(invocation -> withId(invocation.getArgument(0), 1001L));

        OrderResponse response = service.placeOrder(new PlaceOrderRequest(OrderType.DINE_IN, "Ayşe", null, 4L, null,
                null, PaymentMethod.CARD, null, List.of(line(7, 2), line(21, 2))), "waiter");

        assertThat(response.id()).isEqualTo(1001L);
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.table().code()).isEqualTo("A4");
        assertThat(response.createdAt()).isEqualTo(NOW);
        assertThat(response.createdBy()).isEqualTo("waiter");
        assertThat(response.pricing().subtotal()).isEqualByComparingTo("1130.00");
        assertThat(response.pricing().total()).isEqualByComparingTo("1243.00");

        ArgumentCaptor<OrderChangedEvent> event = ArgumentCaptor.forClass(OrderChangedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().kind()).isEqualTo(OrderChangedEvent.Kind.CREATED);
        assertThat(event.getValue().orderId()).isEqualTo(1001L);
        assertThat(event.getValue().tableCode()).isEqualTo("A4");
    }

    @Test
    void refusesSoldOutItems() {
        tea.changeAvailability(false);
        when(menuItems.findAllById(anyIterable())).thenReturn(List.of(tea));

        assertThatThrownBy(() -> service.placeOrder(takeaway(line(21, 1)), "waiter"))
                .isInstanceOfSatisfying(BusinessRuleException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("MENU_ITEM_UNAVAILABLE");
                    assertThat(ex.getParameters()).containsEntry("name", "Demlik Çay");
                });
        verify(orders, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void refusesUnknownItems() {
        when(menuItems.findAllById(anyIterable())).thenReturn(List.of());

        assertThatThrownBy(() -> service.placeOrder(takeaway(line(999, 1)), "waiter"))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("MENU_ITEM_NOT_FOUND"));
    }

    @Test
    void refusesUnknownTables() {
        when(menuItems.findAllById(anyIterable())).thenReturn(List.of(kebap));
        when(tables.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.placeOrder(new PlaceOrderRequest(OrderType.DINE_IN, "Ayşe", null, 99L, null,
                null, PaymentMethod.CARD, null, List.of(line(7, 1))), "waiter"))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("TABLE_NOT_FOUND"));
    }

    @Test
    void refusesOrdersWithAnInvalidCoupon() {
        when(menuItems.findAllById(anyIterable())).thenReturn(List.of(tea));
        when(couponRepository.findByCode("SOFRA20")).thenReturn(Optional.of(new Coupon("SOFRA20", "20%",
                DiscountType.PERCENTAGE, new BigDecimal("20"), new BigDecimal("1000"), null, null)));

        PlaceOrderRequest request = new PlaceOrderRequest(OrderType.TAKEAWAY, "Can", null, null, null, null,
                PaymentMethod.CASH, "sofra20", List.of(line(21, 1)));

        assertThatThrownBy(() -> service.placeOrder(request, "waiter"))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("COUPON_MINIMUM_NOT_MET"));
    }

    @Test
    void quoteReportsAnInvalidCouponInsteadOfFailing() {
        when(menuItems.findAllById(anyIterable())).thenReturn(List.of(kebap));
        when(couponRepository.findByCode("NOPE")).thenReturn(Optional.empty());

        QuoteResponse quote = service.quote(new QuoteRequest(OrderType.DELIVERY, "nope", List.of(line(7, 1))));

        assertThat(quote.coupon().applied()).isFalse();
        assertThat(quote.coupon().errorCode()).isEqualTo("COUPON_NOT_FOUND");
        assertThat(quote.pricing().discount()).isEqualByComparingTo("0");
        assertThat(quote.pricing().deliveryFee()).isEqualByComparingTo("39.90");
        assertThat(quote.amountUntilFreeDelivery()).isEqualByComparingTo("230.00");
    }

    @Test
    void quoteAppliesAValidCoupon() {
        when(menuItems.findAllById(anyIterable())).thenReturn(List.of(kebap));
        when(couponRepository.findByCode("HOSGELDIN")).thenReturn(Optional.of(new Coupon("HOSGELDIN", "10% off",
                DiscountType.PERCENTAGE, new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("150"), null)));

        QuoteResponse quote = service.quote(new QuoteRequest(OrderType.TAKEAWAY, "hosgeldin", List.of(line(7, 2))));

        assertThat(quote.coupon().applied()).isTrue();
        assertThat(quote.coupon().description()).isEqualTo("10% off");
        assertThat(quote.pricing().discount()).isEqualByComparingTo("104.00");
        assertThat(quote.lines()).singleElement().satisfies(l -> assertThat(l.lineTotal()).isEqualByComparingTo("1040.00"));
    }

    @Test
    void advancingAnnouncesTheStatusChange() {
        Order order = Order.place(com.umutvarol.restaurant.support.TestData.takeaway(),
                List.of(OrderLine.of(kebap, 1, null)), new PricingCalculator(new PricingProperties(
                        new BigDecimal("0.10"), BigDecimal.ZERO, BigDecimal.ZERO))
                        .calculate(new BigDecimal("520"), OrderType.TAKEAWAY, null),
                NOW.minusSeconds(300), "waiter");
        withId(order, 1500L);
        when(orders.findById(1500L)).thenReturn(Optional.of(order));

        OrderResponse response = service.advance(1500L, "chef");

        assertThat(response.status()).isEqualTo(OrderStatus.PREPARING);
        assertThat(response.history()).last().satisfies(change -> {
            assertThat(change.by()).isEqualTo("chef");
            assertThat(change.at()).isEqualTo(NOW);
        });
        ArgumentCaptor<OrderChangedEvent> event = ArgumentCaptor.forClass(OrderChangedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().previousStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(event.getValue().status()).isEqualTo(OrderStatus.PREPARING);
    }

    @Test
    void reportsMissingOrders() {
        when(orders.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(42L))
                .isInstanceOfSatisfying(ResourceNotFoundException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("ORDER_NOT_FOUND"));
    }

    private static PlaceOrderRequest takeaway(OrderLineRequest... lines) {
        return new PlaceOrderRequest(OrderType.TAKEAWAY, "Can", null, null, null, null, PaymentMethod.CASH, null,
                List.of(lines));
    }

    private static OrderLineRequest line(long menuItemId, int quantity) {
        return new OrderLineRequest(menuItemId, quantity, null);
    }

    private static Order withId(Order order, long id) {
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }
}
