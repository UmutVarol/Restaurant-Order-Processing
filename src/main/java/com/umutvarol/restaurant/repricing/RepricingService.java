package com.umutvarol.restaurant.repricing;

import com.umutvarol.restaurant.business.Branch;
import com.umutvarol.restaurant.business.BranchContext;
import com.umutvarol.restaurant.business.BranchRepository;
import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.InvalidStateException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.common.money.Money;
import com.umutvarol.restaurant.inventory.RecipeService;
import com.umutvarol.restaurant.inventory.dto.DishCostResponse;
import com.umutvarol.restaurant.menu.MenuCategory;
import com.umutvarol.restaurant.menu.MenuService;
import com.umutvarol.restaurant.menu.dto.MenuItemResponse;
import com.umutvarol.restaurant.order.pricing.PricingCalculator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Collator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inflation tools. A manager raises many prices at once (by a percentage or an amount, rounded to a tidy step), sees
 * every new price before anything changes, and can take the change back. Dishes whose ingredients now take more of
 * the price than the business aims for are flagged with the price that would bring them back.
 */
@Service
@Transactional
public class RepricingService {

    private static final Logger audit = LoggerFactory.getLogger("menu.audit");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MAX_PRICE = new BigDecimal("100000.00");
    private static final int MAX_LINES = 1000;
    /** A change can be taken back for this long; after that the menu has moved on. */
    static final Duration UNDO_WINDOW = Duration.ofDays(30);

    /**
     * What to change and how. {@code expected} carries the prices the manager saw in the preview (by
     * {@link MenuService#key}); applying refuses if any of them changed since, so the same change cannot land twice.
     */
    public record Request(PriceChange.Mode mode, @Nullable BigDecimal value, @Nullable Rounding rounding,
                          @Nullable List<MenuCategory> categories, @Nullable List<Long> itemIds,
                          @Nullable List<Long> excludeItemIds, @Nullable Boolean includeBranchPrices,
                          @Nullable List<ExactPrice> prices, @Nullable Map<String, BigDecimal> expected) {
    }

    public record ExactPrice(Long itemId, BigDecimal price) {
    }

    public record Line(String key, Long itemId, String name, MenuCategory category, @Nullable Long branchId,
                       @Nullable String branch, BigDecimal oldPrice, BigDecimal newPrice, BigDecimal change,
                       @Nullable BigDecimal foodCostBefore, @Nullable BigDecimal foodCostAfter) {
    }

    /** @param averagePercent how much the prices move on average, central prices only */
    public record Preview(Rounding rounding, List<Line> lines, int itemCount, int changed, BigDecimal averagePercent,
                          Map<String, BigDecimal> expected) {
    }

    public record ChangeView(Long id, PriceChange.Mode mode, @Nullable BigDecimal amount, Rounding rounding, int itemCount,
                             Instant createdAt, String createdBy, @Nullable Instant revertedAt, @Nullable String revertedBy,
                             boolean revertible) {
    }

    public record Reverted(ChangeView change, int restored, int skipped) {
    }

    public enum MarginStatus { OK, WARN, HIGH }

    public record Margin(Long itemId, String name, MenuCategory category, BigDecimal price, BigDecimal vatRate,
                         BigDecimal cost, BigDecimal foodCost, BigDecimal suggestedPrice, MarginStatus status) {
    }

    /** @param needing dishes above the target whose suggested price is higher than today's */
    public record Margins(BigDecimal target, Rounding rounding, int needing, List<Margin> items) {
    }

    public record Settings(BigDecimal targetFoodCost, Rounding rounding) {
    }

    private final MenuService menu;
    private final RecipeService recipes;
    private final BranchRepository branches;
    private final PriceChangeRepository changes;
    private final PricingSettingsRepository settings;
    private final Clock clock;

    RepricingService(MenuService menu, RecipeService recipes, BranchRepository branches, PriceChangeRepository changes,
                     PricingSettingsRepository settings, Clock clock) {
        this.menu = menu;
        this.recipes = recipes;
        this.branches = branches;
        this.changes = changes;
        this.settings = settings;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------- bulk change

    @Transactional(readOnly = true)
    public Preview preview(Request request) {
        return compute(request);
    }

    /**
     * Applies the change. Nothing changes unless every price is still the one the preview showed.
     *
     * @throws BusinessRuleException {@code BULK_PRICE_PREVIEW_NEEDED} without the preview's prices, {@code
     *                               BULK_PRICE_NOTHING} when no price would change
     * @throws InvalidStateException {@code PRICES_CHANGED} when a price moved since the preview
     */
    public ChangeView apply(Request request, String actor) {
        if (request.expected() == null || request.expected().isEmpty()) {
            throw new BusinessRuleException("BULK_PRICE_PREVIEW_NEEDED", "Look at the new prices before applying them");
        }
        Preview preview = compute(request);
        List<Line> moving = preview.lines().stream().filter(line -> line.oldPrice().compareTo(line.newPrice()) != 0).toList();
        if (moving.isEmpty()) {
            throw new BusinessRuleException("BULK_PRICE_NOTHING", "No price would change");
        }
        // every line the manager saw must still be what they saw, moving or not
        for (Line line : preview.lines()) {
            BigDecimal seen = request.expected().get(line.key());
            if (seen == null || seen.compareTo(line.oldPrice()) != 0) {
                throw new InvalidStateException("PRICES_CHANGED",
                        "The price of %s changed meanwhile; look at the new prices first".formatted(line.name()),
                        Map.of("name", line.name()));
            }
        }
        List<MenuService.Repriced> done = menu.reprice(
                moving.stream().map(line -> new MenuService.PriceSet(line.itemId(), line.branchId(), line.newPrice())).toList(),
                request.expected(), actor);
        PriceChange change = new PriceChange(request.mode(), request.mode() == PriceChange.Mode.EXACT ? null : request.value(),
                preview.rounding(), clock.instant(), actor);
        done.forEach(line -> change.add(line.itemId(), line.branchId(), line.oldPrice(), line.newPrice()));
        PriceChange saved = changes.save(change);
        audit.info("menu.bulk-price id={} mode={} amount={} rounding={} prices={} by={}", saved.getId(), request.mode(),
                request.value(), preview.rounding(), done.size(), actor);
        return view(saved);
    }

    /**
     * Takes a change back: each price it moved goes back to what it was, unless it was changed again since (that one
     * is left as it is and counted as skipped).
     */
    public Reverted revert(Long id, String actor) {
        PriceChange change = changes.findById(id).orElseThrow(() -> new ResourceNotFoundException("price change", id));
        if (change.getRevertedAt() != null) {
            throw new InvalidStateException("PRICE_CHANGE_REVERTED", "This change was already taken back", Map.of("id", id));
        }
        if (change.getCreatedAt().isBefore(clock.instant().minus(UNDO_WINDOW))) {
            throw new InvalidStateException("PRICE_CHANGE_TOO_OLD", "A change older than 30 days cannot be taken back", Map.of("id", id));
        }
        Map<String, BigDecimal> current = currentPrices(change.getLines().stream().map(PriceChangeLine::getMenuItemId).collect(Collectors.toSet()));
        List<MenuService.PriceSet> back = new ArrayList<>();
        Map<String, BigDecimal> expected = new HashMap<>();
        int skipped = 0;
        for (PriceChangeLine line : change.getLines()) {
            String key = MenuService.key(line.getMenuItemId(), line.getBranchId());
            BigDecimal now = current.get(key);
            if (now == null || now.compareTo(line.getNewPrice()) != 0) {
                skipped++;
                continue;
            }
            back.add(new MenuService.PriceSet(line.getMenuItemId(), line.getBranchId(), line.getOldPrice()));
            expected.put(key, line.getNewPrice());
        }
        if (!back.isEmpty()) {
            menu.reprice(back, expected, actor);
        }
        change.reverted(clock.instant(), actor);
        audit.info("menu.bulk-price.reverted id={} restored={} skipped={} by={}", id, back.size(), skipped, actor);
        return new Reverted(view(change), back.size(), skipped);
    }

    @Transactional(readOnly = true)
    public List<ChangeView> recent() {
        return changes.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 20)).stream().map(this::view).toList();
    }

    private Preview compute(Request request) {
        if (request.mode() == null) {
            throw invalid("Choose how prices change");
        }
        Rounding rounding = request.rounding() != null ? request.rounding() : settings().rounding();
        List<MenuItemResponse> all = menu.list(null, null, true, null);
        Map<Long, MenuItemResponse> byId = all.stream().collect(Collectors.toMap(MenuItemResponse::id, Function.identity()));
        Map<Long, BigDecimal> exact = new LinkedHashMap<>();
        List<MenuItemResponse> chosen;
        if (request.mode() == PriceChange.Mode.EXACT) {
            if (request.prices() == null || request.prices().isEmpty() || request.prices().size() > MAX_LINES) {
                throw invalid("Give the new prices, at most " + MAX_LINES);
            }
            for (ExactPrice price : request.prices()) {
                if (price.itemId() == null || price.price() == null || price.price().signum() <= 0 || price.price().compareTo(MAX_PRICE) > 0) {
                    throw invalid("Every new price must be between 0.01 and 100000");
                }
                if (!byId.containsKey(price.itemId())) {
                    throw new ResourceNotFoundException("menu item", price.itemId());
                }
                exact.put(price.itemId(), Money.normalize(price.price()));
            }
            chosen = exact.keySet().stream().map(byId::get).toList();
        } else {
            BigDecimal value = request.value();
            if (value == null || value.signum() == 0) {
                throw invalid("Give how much prices change");
            }
            if (request.mode() == PriceChange.Mode.PERCENT && (value.compareTo(BigDecimal.valueOf(-50)) < 0 || value.compareTo(BigDecimal.valueOf(200)) > 0)) {
                throw invalid("A percentage between -50 and 200");
            }
            if (request.mode() == PriceChange.Mode.AMOUNT && value.abs().compareTo(BigDecimal.valueOf(10000)) > 0) {
                throw invalid("An amount between -10000 and 10000");
            }
            Set<MenuCategory> categories = request.categories() == null ? Set.of() : new HashSet<>(request.categories());
            Set<Long> only = request.itemIds() == null ? Set.of() : new HashSet<>(request.itemIds());
            Set<Long> excluded = request.excludeItemIds() == null ? Set.of() : new HashSet<>(request.excludeItemIds());
            chosen = all.stream()
                    .filter(dish -> categories.isEmpty() || categories.contains(dish.category()))
                    .filter(dish -> only.isEmpty() || only.contains(dish.id()))
                    .filter(dish -> !excluded.contains(dish.id()))
                    .toList();
        }
        if (chosen.isEmpty()) {
            throw new BusinessRuleException("BULK_PRICE_NOTHING", "No dish is chosen");
        }
        Map<Long, BigDecimal> costs = costs();
        Map<Long, String> branchNames = BranchContext.acrossBranches(() -> branches.findAllByOrderByIdAsc()).stream()
                .collect(Collectors.toMap(Branch::getId, Branch::getName));
        Collator turkish = Collator.getInstance(Locale.forLanguageTag("tr"));
        List<Line> lines = new ArrayList<>();
        for (MenuItemResponse dish : chosen.stream().sorted(Comparator.comparing(MenuItemResponse::category)
                .thenComparing(MenuItemResponse::name, turkish)).toList()) {
            BigDecimal price = exact.containsKey(dish.id()) ? exact.get(dish.id()) : next(dish.price(), request, rounding, dish.name());
            lines.add(line(dish, null, null, dish.price(), price, costs.get(dish.id())));
        }
        if (Boolean.TRUE.equals(request.includeBranchPrices()) && request.mode() != PriceChange.Mode.EXACT) {
            for (MenuService.PriceSet own : menu.branchOwnPrices(chosen.stream().map(MenuItemResponse::id).toList())) {
                MenuItemResponse dish = byId.get(own.itemId());
                lines.add(line(dish, own.branchId(), branchNames.get(own.branchId()), own.price(),
                        next(own.price(), request, rounding, dish.name()), null));
            }
        }
        if (lines.size() > MAX_LINES) {
            throw invalid("At most " + MAX_LINES + " prices at a time");
        }
        List<Line> central = lines.stream().filter(line -> line.branchId() == null).toList();
        BigDecimal average = central.isEmpty() ? BigDecimal.ZERO : central.stream()
                .map(line -> line.change().multiply(HUNDRED).divide(line.oldPrice(), 4, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(central.size()), 1, RoundingMode.HALF_UP);
        Map<String, BigDecimal> expected = new LinkedHashMap<>();
        lines.forEach(line -> expected.put(line.key(), line.oldPrice()));
        int changed = (int) lines.stream().filter(line -> line.oldPrice().compareTo(line.newPrice()) != 0).count();
        return new Preview(rounding, lines, chosen.size(), changed, average, expected);
    }

    private static BigDecimal next(BigDecimal old, Request request, Rounding rounding, String name) {
        BigDecimal raw = request.mode() == PriceChange.Mode.PERCENT
                ? old.multiply(HUNDRED.add(request.value())).divide(HUNDRED, 4, RoundingMode.HALF_UP)
                : old.add(request.value());
        if (raw.signum() <= 0) {
            throw new BusinessRuleException("BULK_PRICE_INVALID", "The new price of %s would be zero or less".formatted(name),
                    Map.of("name", name));
        }
        BigDecimal price = rounding.round(raw);
        if (price.compareTo(MAX_PRICE) > 0) {
            throw new BusinessRuleException("BULK_PRICE_INVALID", "The new price of %s would be too high".formatted(name),
                    Map.of("name", name));
        }
        return price;
    }

    private static Line line(MenuItemResponse dish, @Nullable Long branchId, @Nullable String branch, BigDecimal oldPrice,
                             BigDecimal newPrice, @Nullable BigDecimal cost) {
        return new Line(MenuService.key(dish.id(), branchId), dish.id(), dish.name(), dish.category(), branchId, branch,
                Money.normalize(oldPrice), newPrice, newPrice.subtract(Money.normalize(oldPrice)),
                cost == null ? null : foodCost(cost, oldPrice, dish.vatRate()),
                cost == null ? null : foodCost(cost, newPrice, dish.vatRate()));
    }

    /** The share of the price before VAT that the ingredients take, in percent. */
    private static @Nullable BigDecimal foodCost(BigDecimal cost, BigDecimal price, BigDecimal vatRate) {
        BigDecimal net = PricingCalculator.netOf(price, vatRate);
        return net.signum() <= 0 ? null : cost.multiply(HUNDRED).divide(net, 1, RoundingMode.HALF_UP);
    }

    private Map<String, BigDecimal> currentPrices(Set<Long> itemIds) {
        Map<String, BigDecimal> current = new HashMap<>();
        menu.list(null, null, true, null).stream().filter(dish -> itemIds.contains(dish.id()))
                .forEach(dish -> current.put(MenuService.key(dish.id(), null), dish.price()));
        menu.branchOwnPrices(itemIds).forEach(own -> current.put(MenuService.key(own.itemId(), own.branchId()), own.price()));
        return current;
    }

    /** One portion's ingredient cost of each dish with a recipe; none where nobody wrote recipes. */
    private Map<Long, BigDecimal> costs() {
        return recipes.costs().stream().collect(Collectors.toMap(DishCostResponse::menuItemId, DishCostResponse::cost, (a, b) -> a));
    }

    private ChangeView view(PriceChange change) {
        boolean revertible = change.getRevertedAt() == null && !change.getCreatedAt().isBefore(clock.instant().minus(UNDO_WINDOW));
        return new ChangeView(change.getId(), change.getMode(), change.getAmount(), change.getRounding(), change.getItemCount(),
                change.getCreatedAt(), change.getCreatedBy(), change.getRevertedAt(), change.getRevertedBy(), revertible);
    }

    private static BusinessRuleException invalid(String message) {
        return new BusinessRuleException("BULK_PRICE_RULE", message);
    }

    // ------------------------------------------------------------------------------------------- margins

    /** Every dish with a recipe: how much of its price the ingredients take, against the business's target. */
    @Transactional(readOnly = true)
    public Margins margins() {
        Settings own = settings();
        BigDecimal target = own.targetFoodCost();
        BigDecimal warnUntil = target.multiply(new BigDecimal("1.25"));
        Map<Long, BigDecimal> costs = costs();
        List<Margin> items = new ArrayList<>();
        for (MenuItemResponse dish : menu.list(null, null, true, null)) {
            BigDecimal cost = costs.get(dish.id());
            if (cost == null || cost.signum() <= 0) {
                continue;
            }
            BigDecimal share = foodCost(cost, dish.price(), dish.vatRate());
            if (share == null) {
                continue;
            }
            // the net price at which the cost is exactly the target, with VAT added back, rounded up to a tidy step
            BigDecimal suggested = own.rounding().roundUp(cost.multiply(HUNDRED).divide(target, 4, RoundingMode.HALF_UP)
                    .multiply(HUNDRED.add(dish.vatRate())).divide(HUNDRED, 4, RoundingMode.HALF_UP));
            MarginStatus status = share.compareTo(target) <= 0 ? MarginStatus.OK
                    : share.compareTo(warnUntil) <= 0 ? MarginStatus.WARN : MarginStatus.HIGH;
            items.add(new Margin(dish.id(), dish.name(), dish.category(), dish.price(), dish.vatRate(), cost, share,
                    suggested.max(dish.price()), status));
        }
        items.sort(Comparator.comparing(Margin::foodCost).reversed());
        int needing = (int) items.stream().filter(m -> m.status() != MarginStatus.OK && m.suggestedPrice().compareTo(m.price()) > 0).count();
        return new Margins(target, own.rounding(), needing, items);
    }

    @Transactional(readOnly = true)
    public Settings settings() {
        return settings.findFirstByOrderByIdAsc().map(s -> new Settings(s.getTargetFoodCost(), s.getRounding()))
                .orElse(new Settings(PricingSettings.DEFAULT_TARGET.setScale(2), Rounding.ONE));
    }

    public Settings saveSettings(BigDecimal targetFoodCost, Rounding rounding, String actor) {
        if (targetFoodCost == null || targetFoodCost.compareTo(BigDecimal.valueOf(5)) < 0 || targetFoodCost.compareTo(BigDecimal.valueOf(90)) > 0) {
            throw new BusinessRuleException("PRICING_TARGET_INVALID", "A target between 5 % and 90 %");
        }
        if (rounding == null) {
            throw new BusinessRuleException("PRICING_TARGET_INVALID", "Choose a rounding");
        }
        Instant now = clock.instant();
        PricingSettings own = settings.findFirstByOrderByIdAsc().orElseGet(() -> new PricingSettings(now, actor));
        own.change(targetFoodCost.setScale(2, RoundingMode.HALF_UP), rounding, now, actor);
        settings.save(own);
        return settings();
    }
}
