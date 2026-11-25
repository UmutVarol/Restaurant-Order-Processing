package com.umutvarol.restaurant.planning;

import com.umutvarol.restaurant.business.BranchContext;
import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.inventory.QuantityUnit;
import com.umutvarol.restaurant.inventory.StockService;
import com.umutvarol.restaurant.inventory.StockUnit;
import com.umutvarol.restaurant.inventory.dto.IngredientResponse;
import com.umutvarol.restaurant.menu.MenuCategory;
import com.umutvarol.restaurant.menu.MenuService;
import com.umutvarol.restaurant.menu.dto.MenuItemResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Planning the kitchen's day: how many of each dish a day will likely sell (from the same weekday of past weeks), what
 * those portions need from stock and what is short, which dishes are prepared, and what was thrown away and why.
 */
@Service
@Transactional
public class PlanningService {

    private static final Logger audit = LoggerFactory.getLogger("stock.audit");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MAX_PORTIONS = BigDecimal.valueOf(1000);

    public record ForecastItem(Long menuItemId, String name, String icon, MenuCategory category, int expected, int low,
                               int high, Forecaster.Confidence confidence, boolean prepared, @Nullable String preparedBy) {
    }

    /**
     * @param trendPercent how much busier (or quieter) the last two weeks were, as applied
     * @param weeks        how many of the same weekday the estimate rests on
     */
    public record Prep(LocalDate day, DayOfWeek weekday, int weeks, int trendPercent, List<ForecastItem> dishes,
                       List<NeedLine> ingredients, int shortages, Instant generatedAt) {
    }

    /** @param shortBy how much more is needed than the ledger says is on hand; zero when it is enough or not tracked */
    public record NeedLine(Long ingredientId, String name, StockUnit unit, BigDecimal needed, BigDecimal onHand,
                           boolean tracked, BigDecimal shortBy) {
    }

    /**
     * Something thrown away: a dish's portions ({@code menuItemId}) or an ingredient ({@code ingredientId} in
     * {@code unit}). {@code requestKey}, made by the tablet, records a retried request once.
     */
    public record WasteRequest(@Nullable Long menuItemId, @Nullable Long ingredientId, @Nullable BigDecimal quantity,
                               @Nullable QuantityUnit unit, WasteEntry.@Nullable Reason reason, @Nullable String note,
                               @Nullable String requestKey) {
    }

    public record WasteView(Long id, @Nullable Long menuItemId, @Nullable Long ingredientId, String name, BigDecimal quantity,
                            String unit, WasteEntry.Reason reason, @Nullable String note, BigDecimal cost, Instant at,
                            String by) {
    }

    public record ReasonTotal(WasteEntry.Reason reason, int count, BigDecimal cost) {
    }

    public record ItemTotal(String name, BigDecimal quantity, String unit, BigDecimal cost) {
    }

    /** @param sharePercent waste against what guests paid for completed orders in the period; null without sales */
    public record WasteReport(LocalDate from, LocalDate to, BigDecimal cost, int count, BigDecimal revenue,
                              @Nullable BigDecimal sharePercent, List<ReasonTotal> byReason, List<ItemTotal> topItems,
                              List<WasteView> entries) {
    }

    private final PlanningSalesRepository sales;
    private final PrepCheckRepository checks;
    private final WasteEntryRepository waste;
    private final MenuService menu;
    private final StockService stock;
    private final Clock clock;

    PlanningService(PlanningSalesRepository sales, PrepCheckRepository checks, WasteEntryRepository waste, MenuService menu,
                    StockService stock, Clock clock) {
        this.sales = sales;
        this.checks = checks;
        this.waste = waste;
        this.menu = menu;
        this.stock = stock;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------- forecast and prep

    /** The day's likely sales, prepared dishes and what they need from stock. Days from yesterday to a week ahead. */
    @Transactional(readOnly = true)
    public Prep prep(LocalDate day) {
        requirePlannable(day);
        ZoneId zone = clock.getZone();
        LocalDate first = day.minusWeeks(Forecaster.WEEKS);
        // what was made on each open day before the day planned, dish by dish
        Map<LocalDate, Map<Long, Integer>> byDay = new HashMap<>();
        for (Object[] row : sales.madeBetween(first.atStartOfDay(zone).toInstant(), day.atStartOfDay(zone).toInstant())) {
            LocalDate date = ((Instant) row[0]).atZone(zone).toLocalDate();
            Long dish = (Long) row[1];
            int made = ((Number) row[2]).intValue();
            if (dish != null && made > 0) {
                byDay.computeIfAbsent(date, d -> new HashMap<>()).merge(dish, made, Integer::sum);
            }
        }
        TreeSet<LocalDate> open = new TreeSet<>(byDay.keySet());
        List<LocalDate> sameWeekday = new ArrayList<>();
        for (int week = 1; week <= Forecaster.WEEKS; week++) {
            if (open.contains(day.minusWeeks(week))) {
                sameWeekday.add(day.minusWeeks(week));
            }
        }
        List<LocalDate> recent = open.descendingSet().stream().limit(14).toList();
        long lastFortnight = total(byDay, open.descendingSet().stream().filter(d -> !d.isBefore(day.minusDays(14))).toList());
        long before = total(byDay, open.descendingSet().stream().filter(d -> d.isBefore(day.minusDays(14)) && !d.isBefore(day.minusDays(28))).toList());
        double trend = Forecaster.trend(lastFortnight, before);

        Map<Long, PrepCheck> done = checks.findByPrepDate(day).stream()
                .collect(Collectors.toMap(PrepCheck::getMenuItemId, check -> check, (a, b) -> a));
        List<ForecastItem> dishes = new ArrayList<>();
        for (MenuItemResponse dish : menu.list(null, null, true, BranchContext.current())) {
            List<Integer> weeks = sameWeekday.stream().map(d -> byDay.get(d).getOrDefault(dish.id(), 0)).toList();
            List<Integer> days = recent.stream().map(d -> byDay.get(d).getOrDefault(dish.id(), 0)).toList();
            Forecaster.Estimate estimate = Forecaster.estimate(weeks, days, trend);
            if (estimate.expected() == 0 && estimate.high() == 0) {
                continue;
            }
            PrepCheck check = done.get(dish.id());
            dishes.add(new ForecastItem(dish.id(), dish.name(), dish.icon(), dish.category(), estimate.expected(), estimate.low(),
                    estimate.high(), estimate.confidence(), check != null, check == null ? null : check.getDoneBy()));
        }
        dishes.sort(Comparator.comparing(ForecastItem::expected).reversed().thenComparing(ForecastItem::name));

        Map<Long, BigDecimal> portions = new LinkedHashMap<>();
        dishes.stream().filter(d -> d.expected() > 0).forEach(d -> portions.put(d.menuItemId(), BigDecimal.valueOf(d.expected())));
        List<NeedLine> needs = stock.needsFor(portions).stream().map(need -> new NeedLine(need.ingredientId(), need.name(), need.unit(),
                        need.needed(), need.onHand(), need.tracked(),
                        need.tracked() ? need.needed().subtract(need.onHand().max(BigDecimal.ZERO)).max(BigDecimal.ZERO) : BigDecimal.ZERO))
                .sorted(Comparator.comparing((NeedLine n) -> n.shortBy().signum() > 0 ? 0 : 1).thenComparing(NeedLine::name))
                .toList();
        int shortages = (int) needs.stream().filter(n -> n.shortBy().signum() > 0).count();
        return new Prep(day, day.getDayOfWeek(), sameWeekday.size(), (int) Math.round((trend - 1) * 100), dishes, needs,
                shortages, clock.instant());
    }

    /** Marks a dish prepared for the day, or not; saying the same twice changes nothing. */
    public void markPrepared(LocalDate day, Long menuItemId, boolean prepared, String actor) {
        requirePlannable(day);
        menu.get(menuItemId);
        var existing = checks.findByPrepDateAndMenuItemId(day, menuItemId);
        if (prepared && existing.isEmpty()) {
            try {
                checks.saveAndFlush(new PrepCheck(day, menuItemId, clock.instant(), actor));
            } catch (DataIntegrityViolationException alreadyMarked) {
                // someone else ticked it at the same moment: it is prepared either way
            }
        } else if (!prepared) {
            existing.ifPresent(checks::delete);
        }
    }

    private void requirePlannable(LocalDate day) {
        LocalDate today = LocalDate.now(clock);
        if (day.isBefore(today.minusDays(1)) || day.isAfter(today.plusDays(7))) {
            throw new BusinessRuleException("PREP_DAY_INVALID", "Plan a day from yesterday to a week ahead");
        }
    }

    private static long total(Map<LocalDate, Map<Long, Integer>> byDay, List<LocalDate> days) {
        return days.stream().mapToLong(d -> byDay.get(d).values().stream().mapToLong(Integer::longValue).sum()).sum();
    }

    // ------------------------------------------------------------------------------------------- waste

    /**
     * Records waste. A dish's portions take its recipe's ingredients out of stock; an ingredient goes out as typed.
     * The same {@code requestKey} twice records it once.
     */
    public WasteView recordWaste(WasteRequest request, String actor) {
        if (request.requestKey() != null) {
            if (request.requestKey().isBlank() || request.requestKey().length() > 64) {
                throw new BusinessRuleException("WASTE_INVALID", "The request key must be 1 to 64 characters");
            }
            var earlier = waste.findByRequestKey(request.requestKey());
            if (earlier.isPresent()) {
                return view(earlier.get());
            }
        }
        if ((request.menuItemId() == null) == (request.ingredientId() == null)) {
            throw new BusinessRuleException("WASTE_INVALID", "Say either the dish or the ingredient");
        }
        if (request.reason() == null) {
            throw new BusinessRuleException("WASTE_REASON_REQUIRED", "Say why it was thrown away");
        }
        BigDecimal quantity = request.quantity();
        if (quantity == null || quantity.signum() <= 0 || quantity.compareTo(MAX_PORTIONS.multiply(BigDecimal.valueOf(1000))) > 0) {
            throw new BusinessRuleException("WASTE_INVALID", "Give how much was thrown away");
        }
        String note = request.note() == null || request.note().isBlank() ? null : request.note().strip();
        if (note != null && note.length() > 200) {
            note = note.substring(0, 200);
        }
        String ledgerNote = note == null ? request.reason().name() : request.reason().name() + ": " + note;
        WasteEntry entry;
        if (request.menuItemId() != null) {
            if (quantity.compareTo(MAX_PORTIONS) > 0 || quantity.stripTrailingZeros().scale() > 0) {
                throw new BusinessRuleException("WASTE_INVALID", "Give whole portions, at most 1000");
            }
            MenuItemResponse dish = menu.get(request.menuItemId());
            BigDecimal cost = stock.wasteDish(dish.id(), quantity, ledgerNote, actor);
            entry = new WasteEntry(dish.id(), null, dish.name(), quantity, "PORTION", request.reason(), note, cost,
                    clock.instant(), actor, request.requestKey());
        } else {
            QuantityUnit unit = request.unit();
            if (unit == null) {
                throw new BusinessRuleException("WASTE_INVALID", "Give the unit");
            }
            IngredientResponse ingredient = stock.ingredients().stream().filter(i -> i.id().equals(request.ingredientId())).findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("ingredient", request.ingredientId()));
            BigDecimal cost = stock.wasteIngredient(ingredient.id(), quantity, unit, ledgerNote, actor);
            entry = new WasteEntry(null, ingredient.id(), ingredient.name(), quantity, unit.name(), request.reason(), note, cost,
                    clock.instant(), actor, request.requestKey());
        }
        WasteEntry saved = waste.saveAndFlush(entry);
        audit.info("waste id={} what=\"{}\" quantity={} {} reason={} cost={} by={}", saved.getId(), saved.getName(),
                saved.getQuantity(), saved.getUnit(), saved.getReason(), saved.getCost(), actor);
        return view(saved);
    }

    /** What was thrown away over a period (up to 92 days): its cost, against sales, by reason and by item. */
    @Transactional(readOnly = true)
    public WasteReport wasteReport(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || from.plusDays(92).isBefore(to)) {
            throw new BusinessRuleException("WASTE_PERIOD_INVALID", "Choose a period of at most 92 days");
        }
        ZoneId zone = clock.getZone();
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
        List<WasteEntry> entries = waste.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(start, end);
        BigDecimal cost = entries.stream().map(WasteEntry::getCost).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        BigDecimal revenue = sales.revenueBetween(start, end).setScale(2, RoundingMode.HALF_UP);
        BigDecimal share = revenue.signum() == 0 ? null : cost.multiply(HUNDRED).divide(revenue, 1, RoundingMode.HALF_UP);
        Map<WasteEntry.Reason, List<WasteEntry>> grouped = entries.stream().collect(Collectors.groupingBy(WasteEntry::getReason));
        List<ReasonTotal> byReason = grouped.entrySet().stream()
                .map(e -> new ReasonTotal(e.getKey(), e.getValue().size(),
                        e.getValue().stream().map(WasteEntry::getCost).reduce(BigDecimal.ZERO, BigDecimal::add)))
                .sorted(Comparator.comparing(ReasonTotal::cost).reversed()).toList();
        Map<String, List<WasteEntry>> byItem = entries.stream().collect(Collectors.groupingBy(e -> e.getName() + "\u0000" + e.getUnit()));
        List<ItemTotal> top = byItem.values().stream()
                .map(list -> new ItemTotal(list.getFirst().getName(),
                        list.stream().map(WasteEntry::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add), list.getFirst().getUnit(),
                        list.stream().map(WasteEntry::getCost).reduce(BigDecimal.ZERO, BigDecimal::add)))
                .sorted(Comparator.comparing(ItemTotal::cost).reversed().thenComparing(ItemTotal::name))
                .limit(5).toList();
        return new WasteReport(from, to, cost, entries.size(), revenue, share, byReason, top,
                entries.stream().limit(100).map(PlanningService::view).toList());
    }

    /** Today's waste, for the kitchen's own list. */
    @Transactional(readOnly = true)
    public List<WasteView> today() {
        ZoneId zone = clock.getZone();
        LocalDate today = LocalDate.now(clock);
        return waste.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                today.atStartOfDay(zone).toInstant(), today.plusDays(1).atStartOfDay(zone).toInstant())
                .stream().map(PlanningService::view).toList();
    }

    private static WasteView view(WasteEntry e) {
        return new WasteView(e.getId(), e.getMenuItemId(), e.getIngredientId(), e.getName(), e.getQuantity(), e.getUnit(),
                e.getReason(), e.getNote(), e.getCost(), e.getCreatedAt(), e.getCreatedBy());
    }
}
