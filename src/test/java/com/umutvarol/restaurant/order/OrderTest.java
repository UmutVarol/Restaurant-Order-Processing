package com.umutvarol.restaurant.order;

import static com.umutvarol.restaurant.support.TestData.delivery;
import static com.umutvarol.restaurant.support.TestData.dineIn;
import static com.umutvarol.restaurant.support.TestData.menuItem;
import static com.umutvarol.restaurant.support.TestData.table;
import static com.umutvarol.restaurant.support.TestData.takeaway;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.InvalidStateException;
import com.umutvarol.restaurant.menu.MenuCategory;
import com.umutvarol.restaurant.menu.MenuItem;
import com.umutvarol.restaurant.order.pricing.PriceBreakdown;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OrderTest {

    private static final Instant PLACED_AT = Instant.parse("2026-05-20T17:30:00Z");
    private static final PriceBreakdown PRICE = new PriceBreakdown(BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ONE,
            BigDecimal.ZERO, new BigDecimal("11"));

    private final MenuItem adana = menuItem(1, "Adana Kebap", MenuCategory.MAIN, "520.00");
    private final MenuItem ayran = menuItem(2, "Ayran", MenuCategory.DRINK, "70.00");

    @Test
    void placesAPendingOrderAndRecordsWhoPlacedIt() {
        Order order = Order.place(dineIn(table(3, "A3")),
                List.of(OrderLine.of(adana, 2, "  extra lemon "), OrderLine.of(ayran, 1, " ")),
                PRICE, PLACED_AT, "waiter");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getTable().getCode()).isEqualTo("A3");
        assertThat(order.itemCount()).isEqualTo(3);
        assertThat(order.getLines()).extracting(OrderLine::getNote).containsExactly("extra lemon", null);
        assertThat(order.getLines()).allSatisfy(line -> assertThat(line.getOrder()).isSameAs(order));
        assertThat(order.getHistory()).singleElement().satisfies(change -> {
            assertThat(change.getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(change.getChangedAt()).isEqualTo(PLACED_AT);
            assertThat(change.getChangedBy()).isEqualTo("waiter");
        });
        assertThat(order.statusSince()).isEqualTo(PLACED_AT);
    }

    @Test
    void linesKeepThePriceTheGuestWasCharged() {
        OrderLine line = OrderLine.of(adana, 2, null);

        adana.update("Adana Kebap", "new recipe", MenuCategory.MAIN, new BigDecimal("600.00"), "🍢", 18, true, Set.of());

        assertThat(line.getUnitPrice()).isEqualByComparingTo("520.00");
        assertThat(line.getLineTotal()).isEqualByComparingTo("1040.00");
        assertThat(line.getItemName()).isEqualTo("Adana Kebap");
    }

    @Test
    void rejectsLinesWithoutQuantity() {
        assertThatThrownBy(() -> OrderLine.of(adana, 0, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void needsAtLeastOneLine() {
        assertThatThrownBy(() -> Order.place(takeaway(), List.of(), PRICE, PLACED_AT, "waiter"))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("ORDER_EMPTY"));
    }

    @Test
    void dineInOrdersNeedATable() {
        assertThatThrownBy(() -> Order.place(dineIn(null), List.of(OrderLine.of(adana, 1, null)), PRICE, PLACED_AT, "w"))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("TABLE_REQUIRED"));
    }

    @Test
    void deliveryOrdersNeedAnAddress() {
        assertThatThrownBy(() -> Order.place(delivery(" "), List.of(OrderLine.of(adana, 1, null)), PRICE, PLACED_AT, "w"))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("ADDRESS_REQUIRED"));
    }

    @Test
    void ignoresDetailsThatDoNotApplyToTheOrderType() {
        OrderDetails takeawayWithTable = new OrderDetails(OrderType.TAKEAWAY, " Can ", " ", table(1, "A1"),
                "Moda Cad.", "", PaymentMethod.CASH, null);

        Order order = Order.place(takeawayWithTable, List.of(OrderLine.of(ayran, 1, null)), PRICE, PLACED_AT, "w");

        assertThat(order.getCustomerName()).isEqualTo("Can");
        assertThat(order.getTable()).isNull();
        assertThat(order.getDeliveryAddress()).isNull();
        assertThat(order.getCustomerPhone()).isNull();
        assertThat(order.getNote()).isNull();
    }

    @Test
    void advancesThroughTheDineInLifecycleKeepingAnAuditTrail() {
        Order order = Order.place(dineIn(table(1, "A1")), List.of(OrderLine.of(adana, 1, null)), PRICE, PLACED_AT, "waiter");

        assertThat(order.advance(PLACED_AT.plus(Duration.ofMinutes(2)), "chef")).isEqualTo(OrderStatus.PREPARING);
        assertThat(order.advance(PLACED_AT.plus(Duration.ofMinutes(20)), "chef")).isEqualTo(OrderStatus.READY);
        assertThat(order.advance(PLACED_AT.plus(Duration.ofMinutes(22)), "waiter")).isEqualTo(OrderStatus.SERVED);
        assertThat(order.nextStatus()).contains(OrderStatus.COMPLETED);
        assertThat(order.advance(PLACED_AT.plus(Duration.ofMinutes(70)), "waiter")).isEqualTo(OrderStatus.COMPLETED);

        assertThat(order.getHistory()).extracting(OrderStatusChange::getStatus).containsExactly(
                OrderStatus.PENDING, OrderStatus.PREPARING, OrderStatus.READY, OrderStatus.SERVED, OrderStatus.COMPLETED);
        assertThat(order.getUpdatedAt()).isEqualTo(PLACED_AT.plus(Duration.ofMinutes(70)));
        assertThat(order.statusSince()).isEqualTo(PLACED_AT.plus(Duration.ofMinutes(70)));
        assertThat(order.nextStatus()).isEmpty();
    }

    @Test
    void cannotAdvanceAFinishedOrder() {
        Order order = Order.place(takeaway(), List.of(OrderLine.of(ayran, 1, null)), PRICE, PLACED_AT, "waiter");
        order.cancel("guest left", PLACED_AT.plusSeconds(60), "waiter");

        assertThatThrownBy(() -> order.advance(PLACED_AT.plusSeconds(120), "chef"))
                .isInstanceOfSatisfying(InvalidStateException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("ORDER_NOT_ADVANCEABLE"));
    }

    @Test
    void cancellingRecordsTheReason() {
        Order order = Order.place(takeaway(), List.of(OrderLine.of(ayran, 1, null)), PRICE, PLACED_AT, "waiter");
        order.advance(PLACED_AT.plusSeconds(60), "chef");

        order.cancel("  Item ran out ", PLACED_AT.plusSeconds(120), "waiter");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancellationReason()).isEqualTo("Item ran out");
        assertThat(order.getHistory()).last().extracting(OrderStatusChange::getChangedBy).isEqualTo("waiter");
    }

    @Test
    void cannotCancelOnceTheFoodIsReady() {
        Order order = Order.place(takeaway(), List.of(OrderLine.of(ayran, 1, null)), PRICE, PLACED_AT, "waiter");
        order.advance(PLACED_AT.plusSeconds(60), "chef");
        order.advance(PLACED_AT.plusSeconds(600), "chef");

        assertThatThrownBy(() -> order.cancel("too late", PLACED_AT.plusSeconds(700), "waiter"))
                .isInstanceOfSatisfying(InvalidStateException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("ORDER_NOT_CANCELLABLE"));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.READY);
    }
}
