package com.umutvarol.restaurant.analytics;

import static com.umutvarol.restaurant.support.TestData.dineIn;
import static com.umutvarol.restaurant.support.TestData.menuItem;
import static com.umutvarol.restaurant.support.TestData.table;
import static com.umutvarol.restaurant.support.TestData.takeaway;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

import com.umutvarol.restaurant.analytics.DashboardResponse.DailyPoint;
import com.umutvarol.restaurant.analytics.DashboardResponse.StatusCount;
import com.umutvarol.restaurant.analytics.DashboardResponse.TypeBreakdown;
import com.umutvarol.restaurant.menu.MenuCategory;
import com.umutvarol.restaurant.order.Order;
import com.umutvarol.restaurant.order.OrderDetails;
import com.umutvarol.restaurant.order.OrderLine;
import com.umutvarol.restaurant.order.OrderRepository;
import com.umutvarol.restaurant.order.OrderStatus;
import com.umutvarol.restaurant.order.OrderType;
import com.umutvarol.restaurant.order.pricing.PriceBreakdown;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");
    /** Tuesday 10 March 2026, 15:00 in Istanbul. */
    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    @Mock
    private OrderRepository orders;
    @Mock
    private SalesReportRepository salesReports;

    private AnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new AnalyticsService(orders, salesReports, Clock.fixed(NOW, ISTANBUL));
        when(salesReports.salesByCategory(any(), any())).thenReturn(List.of());
        when(salesReports.topItems(any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void summarisesTheCurrentPeriodAndComparesItWithThePreviousOne() {
        Order today = completed(order(takeaway(), "2026-03-10T09:00:00Z", "100.00"), 12);
        // 01:30 on 10 March in Istanbul, although still 9 March in UTC
        Order afterMidnight = completed(order(dineIn(table(1, "A1")), "2026-03-09T22:30:00Z", "300.00"), 18);
        Order cancelled = order(takeaway(), "2026-03-08T10:00:00Z", "999.00");
        cancelled.cancel("guest left", Instant.parse("2026-03-08T10:05:00Z"), "waiter");
        Order lastWeek = completed(order(takeaway(), "2026-03-02T10:00:00Z", "50.00"), 10);
        when(orders.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(any(), any()))
                .thenReturn(List.of(lastWeek, cancelled, afterMidnight, today));
        when(orders.countByStatusIn(anyCollection())).thenReturn(3L);

        DashboardResponse dashboard = service.dashboard(7);

        assertThat(dashboard.from()).isEqualTo(LocalDate.of(2026, 3, 4));
        assertThat(dashboard.to()).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(dashboard.activeOrders()).isEqualTo(3);

        assertThat(dashboard.current().orders()).isEqualTo(2);
        assertThat(dashboard.current().revenue()).isEqualByComparingTo("400.00");
        assertThat(dashboard.current().averageOrderValue()).isEqualByComparingTo("200.00");
        assertThat(dashboard.current().cancelledOrders()).isEqualTo(1);
        assertThat(dashboard.current().itemsSold()).isEqualTo(2);
        assertThat(dashboard.current().averageKitchenMinutes()).isEqualTo(15.0);

        assertThat(dashboard.previous().orders()).isEqualTo(1);
        assertThat(dashboard.previous().revenue()).isEqualByComparingTo("50.00");

        assertThat(dashboard.daily()).hasSize(7).last().satisfies(point -> {
            assertThat(point.date()).isEqualTo(LocalDate.of(2026, 3, 10));
            assertThat(point.orders()).isEqualTo(2);
            assertThat(point.revenue()).isEqualByComparingTo("400.00");
        });
        assertThat(dashboard.daily()).filteredOn(point -> point.orders() == 0)
                .extracting(DailyPoint::revenue).allSatisfy(revenue -> assertThat(revenue).isZero());

        assertThat(dashboard.hourly()).hasSize(24);
        assertThat(dashboard.hourly().get(1).orders()).isEqualTo(1);  // 01:30 local
        assertThat(dashboard.hourly().get(12).orders()).isEqualTo(1); // 12:00 local

        assertThat(dashboard.statuses()).contains(new StatusCount(OrderStatus.COMPLETED, 2),
                new StatusCount(OrderStatus.CANCELLED, 1));
        assertThat(dashboard.orderTypes()).contains(
                new TypeBreakdown(OrderType.DINE_IN, 1, new BigDecimal("300.00")),
                new TypeBreakdown(OrderType.TAKEAWAY, 1, new BigDecimal("100.00")));
    }

    @Test
    void anEmptyPeriodHasZeroedKpis() {
        when(orders.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(any(), any())).thenReturn(List.of());

        DashboardResponse dashboard = service.dashboard(1);

        assertThat(dashboard.daily()).hasSize(1);
        assertThat(dashboard.current().orders()).isZero();
        assertThat(dashboard.current().averageOrderValue()).isZero();
        assertThat(dashboard.current().averageKitchenMinutes()).isNull();
    }

    private static Order order(OrderDetails details, String createdAt, String total) {
        BigDecimal amount = new BigDecimal(total);
        return Order.place(details,
                List.of(OrderLine.of(menuItem(1, "Mantı", MenuCategory.MAIN, total), 1, null)),
                new PriceBreakdown(amount, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, amount),
                Instant.parse(createdAt), "waiter");
    }

    /** Runs the order through to COMPLETED, reaching READY {@code kitchenMinutes} after it was placed. */
    private static Order completed(Order order, int kitchenMinutes) {
        Instant start = order.getCreatedAt();
        order.advance(start.plus(Duration.ofMinutes(1)), "chef");
        order.advance(start.plus(Duration.ofMinutes(kitchenMinutes)), "chef");
        while (order.nextStatus().isPresent()) {
            order.advance(start.plus(Duration.ofMinutes(kitchenMinutes + 5)), "waiter");
        }
        return order;
    }
}
