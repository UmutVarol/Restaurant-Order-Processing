package com.umutvarol.restaurant.analytics;

import com.umutvarol.restaurant.analytics.DashboardResponse.DailyPoint;
import com.umutvarol.restaurant.analytics.DashboardResponse.HourlyPoint;
import com.umutvarol.restaurant.analytics.DashboardResponse.Kpis;
import com.umutvarol.restaurant.analytics.DashboardResponse.PaymentBreakdown;
import com.umutvarol.restaurant.analytics.DashboardResponse.StatusCount;
import com.umutvarol.restaurant.analytics.DashboardResponse.TypeBreakdown;
import com.umutvarol.restaurant.common.money.Money;
import com.umutvarol.restaurant.order.Order;
import com.umutvarol.restaurant.order.OrderRepository;
import com.umutvarol.restaurant.order.OrderStatus;
import com.umutvarol.restaurant.order.OrderStatusChange;
import com.umutvarol.restaurant.order.OrderType;
import com.umutvarol.restaurant.order.PaymentMethod;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes the manager dashboard. Line-level aggregates (top sellers, category mix) are grouped in the
 * database; order-level series are bucketed in Java using the restaurant's time zone so that days and hours
 * line up with the guest's wall clock regardless of the database's own time zone.
 */
@Service
@Transactional(readOnly = true)
public class AnalyticsService {

    private static final int TOP_ITEMS = 5;
    private static final Predicate<Order> NOT_CANCELLED = order -> order.getStatus() != OrderStatus.CANCELLED;

    private final OrderRepository orders;
    private final SalesReportRepository salesReports;
    private final Clock clock;

    public AnalyticsService(OrderRepository orders, SalesReportRepository salesReports, Clock clock) {
        this.orders = orders;
        this.salesReports = salesReports;
        this.clock = clock;
    }

    public DashboardResponse dashboard(int days) {
        ZoneId zone = clock.getZone();
        LocalDate today = LocalDate.now(clock);
        LocalDate from = today.minusDays(days - 1L);
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = today.plusDays(1).atStartOfDay(zone).toInstant();
        Instant previousStart = from.minusDays(days).atStartOfDay(zone).toInstant();

        List<Order> window = orders.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(previousStart, end);
        List<Order> current = window.stream().filter(o -> !o.getCreatedAt().isBefore(start)).toList();
        List<Order> previous = window.stream().filter(o -> o.getCreatedAt().isBefore(start)).toList();

        return new DashboardResponse(
                from,
                today,
                days,
                kpis(current),
                kpis(previous),
                orders.countByStatusIn(OrderStatus.activeStatuses()),
                daily(current, from, days, zone),
                hourly(current, zone),
                salesReports.salesByCategory(start, end),
                salesReports.topItems(start, end, Limit.of(TOP_ITEMS)),
                statuses(current),
                byType(current),
                byPayment(current));
    }

    private static Kpis kpis(List<Order> orders) {
        List<Order> sold = orders.stream().filter(NOT_CANCELLED).toList();
        BigDecimal revenue = revenue(sold.stream());
        BigDecimal average = sold.isEmpty()
                ? Money.ZERO
                : revenue.divide(BigDecimal.valueOf(sold.size()), Money.SCALE, Money.ROUNDING);
        long itemsSold = sold.stream().mapToLong(Order::itemCount).sum();
        OptionalDouble kitchenMinutes = sold.stream()
                .flatMap(order -> order.getHistory().stream()
                        .filter(change -> change.getStatus() == OrderStatus.READY)
                        .findFirst()
                        .map(OrderStatusChange::getChangedAt)
                        .map(readyAt -> Duration.between(order.getCreatedAt(), readyAt).toSeconds() / 60.0)
                        .stream())
                .mapToDouble(Double::doubleValue)
                .average();
        return new Kpis(revenue, sold.size(), average, orders.size() - sold.size(), itemsSold,
                kitchenMinutes.isPresent() ? round1(kitchenMinutes.getAsDouble()) : null);
    }

    private static List<DailyPoint> daily(List<Order> orders, LocalDate from, int days, ZoneId zone) {
        Map<LocalDate, List<Order>> byDay = orders.stream()
                .filter(NOT_CANCELLED)
                .collect(Collectors.groupingBy(o -> LocalDate.ofInstant(o.getCreatedAt(), zone)));
        return Stream.iterate(from, date -> date.plusDays(1))
                .limit(days)
                .map(date -> {
                    List<Order> dayOrders = byDay.getOrDefault(date, List.of());
                    return new DailyPoint(date, revenue(dayOrders.stream()), dayOrders.size());
                })
                .toList();
    }

    private static List<HourlyPoint> hourly(List<Order> orders, ZoneId zone) {
        Map<Integer, List<Order>> byHour = orders.stream()
                .filter(NOT_CANCELLED)
                .collect(Collectors.groupingBy(o -> o.getCreatedAt().atZone(zone).getHour()));
        return IntStream.range(0, 24)
                .mapToObj(hour -> {
                    List<Order> hourOrders = byHour.getOrDefault(hour, List.of());
                    return new HourlyPoint(hour, hourOrders.size(), revenue(hourOrders.stream()));
                })
                .toList();
    }

    private static List<StatusCount> statuses(List<Order> orders) {
        Map<OrderStatus, Long> counts = countBy(orders.stream(), Order::getStatus, OrderStatus.class);
        return Arrays.stream(OrderStatus.values())
                .map(status -> new StatusCount(status, counts.getOrDefault(status, 0L)))
                .toList();
    }

    private static List<TypeBreakdown> byType(List<Order> orders) {
        Map<OrderType, List<Order>> grouped = orders.stream().filter(NOT_CANCELLED)
                .collect(Collectors.groupingBy(Order::getType, () -> new EnumMap<>(OrderType.class), Collectors.toList()));
        return Arrays.stream(OrderType.values())
                .map(type -> {
                    List<Order> typed = grouped.getOrDefault(type, List.of());
                    return new TypeBreakdown(type, typed.size(), revenue(typed.stream()));
                })
                .toList();
    }

    private static List<PaymentBreakdown> byPayment(List<Order> orders) {
        Map<PaymentMethod, List<Order>> grouped = orders.stream().filter(NOT_CANCELLED)
                .collect(Collectors.groupingBy(Order::getPaymentMethod, () -> new EnumMap<>(PaymentMethod.class),
                        Collectors.toList()));
        return Arrays.stream(PaymentMethod.values())
                .map(method -> {
                    List<Order> paid = grouped.getOrDefault(method, List.of());
                    return new PaymentBreakdown(method, paid.size(), revenue(paid.stream()));
                })
                .toList();
    }

    private static BigDecimal revenue(Stream<Order> orders) {
        return orders.map(order -> order.getPricing().total()).reduce(Money.ZERO, BigDecimal::add);
    }

    private static <K extends Enum<K>> Map<K, Long> countBy(Stream<Order> orders, Function<Order, K> key, Class<K> type) {
        return orders.collect(Collectors.groupingBy(key, () -> new EnumMap<>(type), Collectors.counting()));
    }

    private static double round1(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
