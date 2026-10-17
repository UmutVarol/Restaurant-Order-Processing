package com.umutvarol.restaurant.bootstrap;

import com.umutvarol.restaurant.common.money.Money;
import com.umutvarol.restaurant.coupon.Coupon;
import com.umutvarol.restaurant.coupon.CouponRepository;
import com.umutvarol.restaurant.menu.MenuCategory;
import com.umutvarol.restaurant.menu.MenuItem;
import com.umutvarol.restaurant.menu.MenuItemRepository;
import com.umutvarol.restaurant.order.Order;
import com.umutvarol.restaurant.order.OrderDetails;
import com.umutvarol.restaurant.order.OrderLine;
import com.umutvarol.restaurant.order.OrderRepository;
import com.umutvarol.restaurant.order.OrderStatus;
import com.umutvarol.restaurant.order.OrderType;
import com.umutvarol.restaurant.order.PaymentMethod;
import com.umutvarol.restaurant.order.pricing.PriceBreakdown;
import com.umutvarol.restaurant.order.pricing.PricingCalculator;
import com.umutvarol.restaurant.table.DiningTable;
import com.umutvarol.restaurant.table.DiningTableRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generates a believable order history for demo mode: lunch and dinner peaks, busier weekends, a realistic
 * mix of dine-in, takeaway and delivery, occasional coupons and cancellations — plus a handful of orders
 * placed in the last half hour so the kitchen display and floor plan are alive whenever the app starts.
 *
 * <p>Orders are built through the real domain model ({@link Order#place}, {@link Order#advance}) and priced by
 * the real {@link PricingCalculator}, so seeded data obeys exactly the same rules as live data.
 */
@Component
@org.springframework.core.annotation.Order(2)
class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final List<String> GUESTS = List.of(
            "Ayşe Kaya", "Mehmet Yıldız", "Zeynep Arslan", "Emre Tunç", "Elif Doğan", "Burak Şahin", "Selin Öztürk",
            "Can Erdem", "Deniz Bulut", "Ece Güneş", "Mert Çelik", "Gizem Sarı", "Onur Koç", "Derya Metin",
            "Kerem Aydın", "İrem Toprak", "Barış Yalçın", "Melis Çakır", "Hakan Oral", "Sena Uslu", "Tolga İnce",
            "Pınar Ekinci", "Cem Çınar", "Buse Nar", "Emma Walker", "Lucas Martin", "Sofia Rossi", "Noah Fischer");

    private static final List<String> ADDRESSES = List.of(
            "Moda Cad. No:12 D:3, Kadıköy", "Bağdat Cad. No:245 D:8, Kadıköy", "Vali Konağı Cad. No:41, Şişli",
            "Akarsu Yokuşu No:7, Cihangir, Beyoğlu", "Çarşı Cad. No:3 D:12, Levent, Beşiktaş",
            "Bebek Yolu No:15, Arnavutköy, Beşiktaş", "İcadiye Cad. No:22, Kuzguncuk, Üsküdar",
            "Abdi İpekçi Cad. No:61 D:4, Nişantaşı", "Fenerbahçe Cad. No:90, Kadıköy");

    private static final List<String> LINE_NOTES = List.of(
            "No onions", "Extra lemon", "Mild, please", "Sauce on the side", "No tomatoes", "Extra spicy");

    private static final List<String> ORDER_NOTES = List.of(
            "Birthday — bring a candle 🎂", "Allergic to walnuts", "Please ring the bell twice",
            "High chair needed", "Guests are in a hurry");

    private static final List<String> CANCEL_REASONS = List.of(
            "Guest changed their mind", "Item ran out in the kitchen", "Duplicate order", "Guest left");

    private final DemoProperties properties;
    private final OrderRepository orders;
    private final MenuItemRepository menuItems;
    private final DiningTableRepository tables;
    private final CouponRepository coupons;
    private final PricingCalculator pricing;
    private final Clock clock;

    DemoDataSeeder(DemoProperties properties, OrderRepository orders, MenuItemRepository menuItems,
                   DiningTableRepository tables, CouponRepository coupons, PricingCalculator pricing, Clock clock) {
        this.properties = properties;
        this.orders = orders;
        this.menuItems = menuItems;
        this.tables = tables;
        this.coupons = coupons;
        this.pricing = pricing;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.enabled() || orders.count() > 0) {
            return;
        }
        Generator generator = new Generator(new Random(properties.seed()), clock.instant(), clock.getZone(),
                menuItems.findByArchivedFalse(), tables.findAllByOrderByIdAsc(), redeemableCoupons());
        List<Order> generated = generator.generate(properties.historyDays());
        orders.saveAll(generated);
        log.info("Demo mode: seeded {} orders covering the last {} days", generated.size(), properties.historyDays());
    }

    private List<Coupon> redeemableCoupons() {
        Instant now = clock.instant();
        return coupons.findByActiveTrueOrderByCodeAsc().stream()
                .filter(c -> c.getValidUntil() == null || c.getValidUntil().isAfter(now))
                .toList();
    }

    /** A scheduled status change of a simulated order. */
    private record Step(OrderStatus status, Instant at, String actor) {
    }

    /** One simulated order before it is materialised: when it happens and how it will unfold. */
    private record Draft(Instant createdAt, OrderType type, List<OrderLine> lines, List<Step> steps,
                         String cancelReason) {

        /** The status the order has reached by {@code now}. */
        OrderStatus statusAt(Instant now) {
            return steps.stream().filter(step -> !step.at().isAfter(now)).map(Step::status)
                    .reduce((first, second) -> second).orElse(OrderStatus.PENDING);
        }
    }

    private final class Generator {

        private final Random random;
        private final Instant now;
        private final ZoneId zone;
        private final Map<MenuCategory, List<MenuItem>> menu = new EnumMap<>(MenuCategory.class);
        private final List<DiningTable> diningTables;
        private final List<Coupon> availableCoupons;

        Generator(Random random, Instant now, ZoneId zone, List<MenuItem> items, List<DiningTable> diningTables,
                  List<Coupon> availableCoupons) {
            this.random = random;
            this.now = now;
            this.zone = zone;
            this.diningTables = diningTables;
            this.availableCoupons = availableCoupons;
            for (MenuItem item : items) {
                if (item.isOrderable()) {
                    menu.computeIfAbsent(item.getCategory(), category -> new ArrayList<>()).add(item);
                }
            }
        }

        List<Order> generate(int historyDays) {
            if (diningTables.isEmpty() || menu.get(MenuCategory.MAIN) == null) {
                return List.of();
            }
            List<Draft> drafts = new ArrayList<>();
            LocalDate today = LocalDate.ofInstant(now, zone);
            Instant liveWindowStart = now.minus(Duration.ofMinutes(35));

            LocalDate firstDay = today.minusDays(Math.max(historyDays - 1, 0));
            for (LocalDate day = firstDay; !day.isAfter(today); day = day.plusDays(1)) {
                boolean weekend = day.getDayOfWeek() == DayOfWeek.FRIDAY || day.getDayOfWeek() == DayOfWeek.SATURDAY
                        || day.getDayOfWeek() == DayOfWeek.SUNDAY;
                double growth = 1 + 0.03 * (day.toEpochDay() - firstDay.toEpochDay()); // business is picking up
                int count = (int) Math.round((22 + random.nextInt(10) + (weekend ? 12 : 0)) * growth);
                for (int i = 0; i < count; i++) {
                    Instant createdAt = day.atTime(sampleTimeOfDay()).atZone(zone).toInstant();
                    if (createdAt.isBefore(liveWindowStart)) {
                        drafts.add(draft(createdAt));
                    }
                }
            }
            // A steady trickle of fresh orders so the kitchen always has work, whatever the time of day.
            for (int minutesAgo : new int[] {31, 26, 22, 18, 14, 11, 8, 5, 3, 1}) {
                drafts.add(draft(now.minus(Duration.ofMinutes(minutesAgo)).minusSeconds(random.nextInt(50))));
            }

            drafts.sort(Comparator.comparing(Draft::createdAt));
            return materialise(drafts);
        }

        private List<Order> materialise(List<Draft> drafts) {
            Set<Long> occupiedTables = new HashSet<>();
            List<Order> result = new ArrayList<>(drafts.size());
            for (Draft draft : drafts) {
                OrderType type = draft.type();
                DiningTable table = null;
                if (type == OrderType.DINE_IN) {
                    boolean stillSeated = draft.statusAt(now).isActive();
                    table = stillSeated ? freeTable(occupiedTables) : pick(diningTables);
                    if (table == null) {
                        type = OrderType.TAKEAWAY; // dining room is full — the guest takes it away
                    } else if (stillSeated) {
                        occupiedTables.add(table.getId());
                    }
                }
                result.add(build(draft, type, table));
            }
            return result;
        }

        private Order build(Draft draft, OrderType type, DiningTable table) {
            BigDecimal subtotal = draft.lines().stream().map(OrderLine::getLineTotal).reduce(Money.ZERO, BigDecimal::add);
            Coupon coupon = random.nextDouble() < 0.12 ? applicableCoupon(subtotal) : null;
            PriceBreakdown price = pricing.calculate(subtotal, type, coupon);
            String guest = pick(GUESTS);
            boolean delivery = type == OrderType.DELIVERY;
            OrderDetails details = new OrderDetails(
                    type,
                    guest,
                    delivery || random.nextDouble() < 0.3 ? phoneNumber() : null,
                    table,
                    delivery ? pick(ADDRESSES) : null,
                    random.nextDouble() < 0.06 ? pick(ORDER_NOTES) : null,
                    paymentMethod(type),
                    coupon == null ? null : coupon.getCode());

            Order order = Order.place(details, draft.lines(), price, draft.createdAt(), "waiter");
            for (Step step : stepsFor(draft, type)) {
                if (step.at().isAfter(now)) {
                    break;
                }
                if (step.status() == OrderStatus.CANCELLED) {
                    order.cancel(draft.cancelReason(), step.at(), step.actor());
                } else {
                    order.advance(step.at(), step.actor());
                }
            }
            return order;
        }

        private Draft draft(Instant createdAt) {
            OrderType type = orderType();
            List<OrderLine> lines = lines(type);
            int kitchenMinutes = lines.stream()
                    .mapToInt(line -> prepTime(line.getMenuItemId()))
                    .max().orElse(10) + random.nextInt(7);
            boolean cancelled = createdAt.isBefore(now.minus(Duration.ofMinutes(40))) && random.nextDouble() < 0.035;
            return new Draft(createdAt, type, lines, plan(type, createdAt, kitchenMinutes, cancelled),
                    cancelled ? pick(CANCEL_REASONS) : null);
        }

        private List<Step> stepsFor(Draft draft, OrderType type) {
            return type == draft.type() ? draft.steps()
                    : plan(type, draft.createdAt(), 12, draft.statusAt(now) == OrderStatus.CANCELLED);
        }

        private List<Step> plan(OrderType type, Instant createdAt, int kitchenMinutes, boolean cancelled) {
            List<Step> steps = new ArrayList<>();
            Instant preparing = createdAt.plus(minutes(1, 4));
            if (cancelled) {
                Instant cancelAt = createdAt.plus(minutes(1, 7));
                if (cancelAt.isAfter(preparing)) {
                    steps.add(new Step(OrderStatus.PREPARING, preparing, "chef"));
                }
                steps.add(new Step(OrderStatus.CANCELLED, cancelAt, "waiter"));
                return steps;
            }
            Instant ready = preparing.plus(Duration.ofMinutes(kitchenMinutes)).plusSeconds(random.nextInt(120));
            steps.add(new Step(OrderStatus.PREPARING, preparing, "chef"));
            steps.add(new Step(OrderStatus.READY, ready, "chef"));
            switch (type) {
                case DINE_IN -> {
                    Instant served = ready.plus(minutes(1, 4));
                    steps.add(new Step(OrderStatus.SERVED, served, "waiter"));
                    steps.add(new Step(OrderStatus.COMPLETED, served.plus(minutes(25, 60)), "waiter"));
                }
                case TAKEAWAY -> steps.add(new Step(OrderStatus.COMPLETED, ready.plus(minutes(3, 12)), "waiter"));
                case DELIVERY -> {
                    Instant dispatched = ready.plus(minutes(2, 7));
                    steps.add(new Step(OrderStatus.OUT_FOR_DELIVERY, dispatched, "waiter"));
                    steps.add(new Step(OrderStatus.COMPLETED, dispatched.plus(minutes(12, 30)), "waiter"));
                }
            }
            return steps;
        }

        private List<OrderLine> lines(OrderType type) {
            int guests = type == OrderType.DINE_IN ? 1 + random.nextInt(4) : 1 + random.nextInt(2);
            Map<MenuItem, Integer> basket = new LinkedHashMap<>();
            for (int guest = 0; guest < guests; guest++) {
                if (random.nextDouble() < 0.92) {
                    basket.merge(weightedPick(MenuCategory.MAIN), 1, Integer::sum);
                }
                if (random.nextDouble() < 0.75) {
                    basket.merge(weightedPick(MenuCategory.DRINK), 1, Integer::sum);
                }
            }
            if (random.nextDouble() < 0.5) {
                basket.merge(weightedPick(MenuCategory.STARTER), 1 + random.nextInt(2), Integer::sum);
            }
            if (random.nextDouble() < 0.38) {
                basket.merge(weightedPick(MenuCategory.DESSERT), 1 + random.nextInt(guests), Integer::sum);
            }
            if (basket.isEmpty()) {
                basket.put(weightedPick(MenuCategory.MAIN), 1);
            }
            List<OrderLine> lines = new ArrayList<>();
            basket.forEach((item, quantity) -> lines.add(OrderLine.of(item, quantity,
                    random.nextDouble() < 0.07 ? pick(LINE_NOTES) : null)));
            return lines;
        }

        private MenuItem weightedPick(MenuCategory category) {
            List<MenuItem> candidates = menu.getOrDefault(category, menu.get(MenuCategory.MAIN));
            int totalWeight = candidates.stream().mapToInt(item -> item.isPopular() ? 3 : 1).sum();
            int target = random.nextInt(totalWeight);
            for (MenuItem item : candidates) {
                target -= item.isPopular() ? 3 : 1;
                if (target < 0) {
                    return item;
                }
            }
            return candidates.getLast();
        }

        private int prepTime(Long menuItemId) {
            return menu.values().stream().flatMap(List::stream)
                    .filter(item -> item.getId().equals(menuItemId))
                    .mapToInt(MenuItem::getPrepTimeMinutes)
                    .findFirst().orElse(10);
        }

        private LocalTime sampleTimeOfDay() {
            double p = random.nextDouble();
            double minuteOfDay;
            if (p < 0.38) {
                minuteOfDay = 12 * 60 + 50 + random.nextGaussian() * 45;   // lunch rush
            } else if (p < 0.88) {
                minuteOfDay = 19 * 60 + 50 + random.nextGaussian() * 70;   // dinner rush
            } else {
                minuteOfDay = 10 * 60 + random.nextDouble() * 13 * 60;     // the rest of the day
            }
            minuteOfDay = Math.clamp(minuteOfDay, 10 * 60.0, 23 * 60 + 30.0);
            return LocalTime.MIN.plusSeconds((long) (minuteOfDay * 60));
        }

        private OrderType orderType() {
            double p = random.nextDouble();
            return p < 0.56 ? OrderType.DINE_IN : p < 0.78 ? OrderType.TAKEAWAY : OrderType.DELIVERY;
        }

        private PaymentMethod paymentMethod(OrderType type) {
            double p = random.nextDouble();
            if (type == OrderType.DELIVERY) {
                return p < 0.55 ? PaymentMethod.ONLINE : p < 0.85 ? PaymentMethod.CARD : PaymentMethod.CASH;
            }
            return p < 0.68 ? PaymentMethod.CARD : PaymentMethod.CASH;
        }

        private Coupon applicableCoupon(BigDecimal subtotal) {
            List<Coupon> applicable = availableCoupons.stream()
                    .filter(c -> subtotal.compareTo(c.getMinimumSubtotal()) >= 0)
                    .toList();
            return applicable.isEmpty() ? null : pick(applicable);
        }

        private DiningTable freeTable(Set<Long> occupied) {
            List<DiningTable> free = diningTables.stream().filter(t -> !occupied.contains(t.getId())).toList();
            return free.isEmpty() ? null : pick(free);
        }

        private String phoneNumber() {
            return "+90 5%02d %03d %02d %02d".formatted(30 + random.nextInt(25), random.nextInt(1000),
                    random.nextInt(100), random.nextInt(100));
        }

        private Duration minutes(int min, int max) {
            return Duration.ofSeconds(min * 60L + random.nextInt((max - min) * 60 + 1));
        }

        private <T> T pick(List<T> values) {
            return values.get(random.nextInt(values.size()));
        }
    }
}
