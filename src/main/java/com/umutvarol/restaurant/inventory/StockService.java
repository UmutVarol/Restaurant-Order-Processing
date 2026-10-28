package com.umutvarol.restaurant.inventory;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.InvalidStateException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.inventory.dto.IngredientRequest;
import com.umutvarol.restaurant.inventory.dto.IngredientResponse;
import com.umutvarol.restaurant.inventory.dto.MovementRequest;
import com.umutvarol.restaurant.inventory.dto.MovementResponse;
import com.umutvarol.restaurant.inventory.dto.StockReportResponse;
import com.umutvarol.restaurant.menu.MenuService;
import com.umutvarol.restaurant.order.Order;
import com.umutvarol.restaurant.order.OrderChangedEvent;
import com.umutvarol.restaurant.order.OrderLine;
import com.umutvarol.restaurant.order.OrderLineOption;
import com.umutvarol.restaurant.order.OrderRepository;
import com.umutvarol.restaurant.order.OrderStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The stock ledger. Balances are always the sum of an ingredient's movements; a completed order records what its
 * dishes used, people record deliveries, waste and counts. When a tracked ingredient runs out, the dishes made
 * with it are sold out until it is restocked.
 */
@Service
@Transactional
public class StockService {

    private static final Logger audit = LoggerFactory.getLogger("stock.audit");
    private static final int MAX_MOVEMENTS = 200;
    private static final long MAX_REPORT_DAYS = 366;

    private final IngredientRepository ingredients;
    private final StockMovementRepository movements;
    private final RecipeLineRepository recipes;
    private final OrderRepository orders;
    private final MenuService menu;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    StockService(IngredientRepository ingredients, StockMovementRepository movements, RecipeLineRepository recipes,
                 OrderRepository orders, MenuService menu, ApplicationEventPublisher events, Clock clock) {
        this.ingredients = ingredients;
        this.movements = movements;
        this.recipes = recipes;
        this.orders = orders;
        this.menu = menu;
        this.events = events;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------- ingredients

    @Transactional(readOnly = true)
    public List<IngredientResponse> ingredients() {
        List<Ingredient> all = ingredients.findByArchivedFalseOrderByNameAsc();
        Map<Long, BigDecimal> balances = balances(all.stream().map(Ingredient::getId).toList());
        return all.stream().map(ingredient -> response(ingredient, balance(balances, ingredient.getId()))).toList();
    }

    public IngredientResponse create(IngredientRequest request) {
        requireUniqueName(request.name(), -1L);
        Ingredient ingredient = ingredients.save(new Ingredient(request.name(), request.unit(), request.cost(),
                request.lowStockLevel(), request.trackStockOrDefault()));
        return response(ingredient, BigDecimal.ZERO);
    }

    /** The unit can only change while nothing counts in it yet: no recipe uses the ingredient and it never moved. */
    public IngredientResponse update(Long id, IngredientRequest request) {
        Ingredient ingredient = find(id);
        requireUniqueName(request.name(), id);
        if (request.unit() != ingredient.getUnit()) {
            if (recipes.isUsed(id) || movements.existsByIngredientId(id)) {
                throw new InvalidStateException("INGREDIENT_UNIT_LOCKED",
                        "%s is already counted in %s".formatted(ingredient.getName(), ingredient.getUnit()),
                        Map.of("name", ingredient.getName()));
            }
            ingredient.changeUnit(request.unit());
        }
        BigDecimal balance = movements.balanceOf(id);
        boolean wasLow = ingredient.isLow(balance);
        ingredient.update(request.name(), request.cost(), request.lowStockLevel(), request.trackStockOrDefault());
        // Tracking switched on or off can sell dishes out or bring them back; a new low level can raise a warning.
        announce(Map.of(ingredient, wasLow), clock.instant());
        return response(ingredient, balance);
    }

    /** Removes an ingredient from the lists; its history stays in the ledger. */
    public void archive(Long id) {
        Ingredient ingredient = find(id);
        if (recipes.isUsed(id)) {
            throw new InvalidStateException("INGREDIENT_IN_USE", "%s is in a recipe".formatted(ingredient.getName()),
                    Map.of("name", ingredient.getName()));
        }
        ingredient.archive();
    }

    // ------------------------------------------------------------------------------------------- movements

    /**
     * Records a delivery, waste or count. A count takes a lock on the ingredient, so two people counting at once
     * cannot both apply the same difference.
     */
    public MovementResponse record(Long id, MovementRequest request, String actor) {
        Ingredient ingredient = request.kind() == MovementKind.COUNT
                ? ingredients.findForUpdate(id).filter(i -> !i.isArchived())
                        .orElseThrow(() -> new ResourceNotFoundException("ingredient", id))
                : find(id);
        BigDecimal quantity = request.unit().toBase(request.quantity(), ingredient.getUnit());
        Instant now = clock.instant();
        BigDecimal before = movements.balanceOf(id);
        String note = blankToNull(request.reason());
        StockMovement movement = switch (request.kind()) {
            case DELIVERY -> {
                requirePositive(quantity);
                BigDecimal cost = request.unitCost() != null ? request.unitCost() : ingredient.getCost();
                ingredient.repriced(cost);
                yield StockMovement.delivery(ingredient, quantity, cost, blankToNull(request.supplier()), note, actor, now);
            }
            case WASTE -> {
                requirePositive(quantity);
                if (note == null) {
                    throw new BusinessRuleException("WASTE_REASON_REQUIRED", "Say why it was thrown away");
                }
                yield StockMovement.waste(ingredient, quantity, note, actor, now);
            }
            case COUNT -> StockMovement.count(ingredient, quantity.subtract(before), note, actor, now);
            case SALE -> throw new BusinessRuleException("MOVEMENT_KIND_NOT_ALLOWED",
                    "Sales are recorded by completed orders, not by hand");
        };
        movements.save(movement);
        audit.info("stock {} ingredient={} name=\"{}\" quantity={} by={}", movement.getKind(), id,
                ingredient.getName(), movement.getQuantity(), actor);
        announce(Map.of(ingredient, ingredient.isLow(before)), now);
        return MovementResponse.from(movement);
    }

    @Transactional(readOnly = true)
    public List<MovementResponse> movements(Long id, int limit) {
        find(id);
        return movements.findByIngredientIdOrderByCreatedAtDescIdDesc(id, PageRequest.of(0, Math.clamp(limit, 1, MAX_MOVEMENTS)))
                .stream().map(MovementResponse::from).toList();
    }

    // ------------------------------------------------------------------------------------------- sales

    /**
     * Records what a completed order used, in the same transaction that completes it. Voided units were never made,
     * so they use nothing; comped units were made and eaten, so they do. Runs once per order.
     */
    @EventListener
    void onOrderChanged(OrderChangedEvent event) {
        if (event.kind() != OrderChangedEvent.Kind.STATUS_CHANGED || event.status() != OrderStatus.COMPLETED
                || movements.existsByOrderId(event.orderId())) {
            return;
        }
        Order order = orders.findById(event.orderId()).orElseThrow();
        Map<Long, BigDecimal> used = usage(order);
        if (used.isEmpty()) {
            return;
        }
        Map<Long, Ingredient> byId = ingredients.findAllById(used.keySet()).stream()
                .collect(Collectors.toMap(Ingredient::getId, Function.identity()));
        Map<Long, BigDecimal> before = balances(byId.keySet());
        Instant now = clock.instant();
        Map<Ingredient, Boolean> wasLow = new HashMap<>();
        used.forEach((ingredientId, quantity) -> {
            Ingredient ingredient = byId.get(ingredientId);
            if (ingredient != null && quantity.signum() > 0) {
                movements.save(StockMovement.sale(ingredient, quantity, order.getId(), now));
                wasLow.put(ingredient, ingredient.isLow(balance(before, ingredientId)));
            }
        });
        announce(wasLow, now);
    }

    /** Ingredient → base quantity a whole order used, from each line's recipe and the options picked on it. */
    private Map<Long, BigDecimal> usage(Order order) {
        Set<Long> dishes = order.getLines().stream().map(OrderLine::getMenuItemId).collect(Collectors.toSet());
        Map<Long, List<RecipeLine>> byDish = recipes.findByMenuItemIdIn(dishes).stream()
                .collect(Collectors.groupingBy(RecipeLine::getMenuItemId));
        Map<Long, BigDecimal> total = new LinkedHashMap<>();
        for (OrderLine line : order.getLines()) {
            int made = line.getQuantity() - line.getVoidedQuantity();
            List<RecipeLine> recipe = byDish.getOrDefault(line.getMenuItemId(), List.of());
            if (made <= 0 || recipe.isEmpty()) {
                continue;
            }
            portion(recipe, line.getOptions()).forEach((ingredient, quantity) ->
                    total.merge(ingredient, quantity.multiply(BigDecimal.valueOf(made)), BigDecimal::add));
        }
        return total;
    }

    /** One portion: the dish's own ingredients, then each picked option adds or swaps in its own. */
    static Map<Long, BigDecimal> portion(List<RecipeLine> recipe, List<OrderLineOption> options) {
        Map<Long, BigDecimal> portion = new LinkedHashMap<>();
        recipe.stream().filter(RecipeLine::isBase)
                .forEach(line -> portion.merge(line.getIngredientId(), line.getQuantity(), BigDecimal::add));
        for (OrderLineOption option : options) {
            for (RecipeLine line : recipe) {
                if (line.appliesTo(option.getGroupName(), option.getChoiceName())) {
                    if (line.getReplacesIngredientId() != null) {
                        portion.remove(line.getReplacesIngredientId());
                    }
                    portion.merge(line.getIngredientId(), line.getQuantity(), BigDecimal::add);
                }
            }
        }
        return portion;
    }

    // ------------------------------------------------------------------------------------------- availability

    /**
     * Works out which of these dishes have an ingredient that ran out, sells them out or back in, and tells the
     * screens. Called after stock moves and after a recipe changes.
     */
    void refreshDishes(Collection<Long> dishIds) {
        Availability changed = availability(dishIds);
        StockChangedEvent event = new StockChangedEvent(List.of(), changed.soldOut(), changed.backOnSale(), clock.instant());
        if (event.matters()) {
            events.publishEvent(event);
        }
    }

    private record Availability(List<String> soldOut, List<String> backOnSale) {
    }

    private Availability availability(Collection<Long> dishIds) {
        if (dishIds.isEmpty()) {
            return new Availability(List.of(), List.of());
        }
        List<RecipeLine> lines = recipes.findByMenuItemIdIn(dishIds).stream().filter(RecipeLine::isBase).toList();
        Set<Long> used = lines.stream().map(RecipeLine::getIngredientId).collect(Collectors.toSet());
        Map<Long, Ingredient> byId = ingredients.findAllById(used).stream()
                .collect(Collectors.toMap(Ingredient::getId, Function.identity()));
        Map<Long, BigDecimal> balances = balances(used);
        Map<Long, Boolean> out = new HashMap<>();
        dishIds.forEach(dish -> out.put(dish, false));
        for (RecipeLine line : lines) {
            Ingredient ingredient = byId.get(line.getIngredientId());
            if (ingredient != null && ingredient.isOut(balance(balances, ingredient.getId()))) {
                out.put(line.getMenuItemId(), true);
            }
        }
        Map<Long, Boolean> soldOut = out.entrySet().stream().filter(Map.Entry::getValue)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Map<Long, Boolean> backIn = out.entrySet().stream().filter(entry -> !entry.getValue())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return new Availability(menu.markStockOut(soldOut), menu.markStockOut(backIn));
    }

    /**
     * After stock moved: re-checks the dishes made with these ingredients and warns about any ingredient that just
     * reached its low level.
     *
     * @param wasLow whether each ingredient was already low before the change
     */
    private void announce(Map<Ingredient, Boolean> wasLow, Instant at) {
        if (wasLow.isEmpty()) {
            return;
        }
        List<Long> ids = wasLow.keySet().stream().map(Ingredient::getId).toList();
        Map<Long, BigDecimal> after = balances(ids);
        List<String> low = wasLow.entrySet().stream()
                .filter(entry -> !entry.getValue() && entry.getKey().isLow(balance(after, entry.getKey().getId())))
                .map(entry -> entry.getKey().getName())
                .sorted()
                .toList();
        if (!low.isEmpty()) {
            audit.info("stock LOW {}", low);
        }
        Availability changed = availability(recipes.dishesUsing(ids));
        StockChangedEvent event = new StockChangedEvent(low, changed.soldOut(), changed.backOnSale(), at);
        if (event.matters()) {
            events.publishEvent(event);
        }
    }

    // ------------------------------------------------------------------------------------------- report

    /**
     * Theoretical against actual use between two days (both included), in the restaurant's time zone.
     *
     * @throws BusinessRuleException {@code REPORT_PERIOD_INVALID} for a period that ends before it starts or spans
     *                               more than a year
     */
    @Transactional(readOnly = true)
    public StockReportResponse report(LocalDate fromDay, LocalDate toDay) {
        if (toDay.isBefore(fromDay) || ChronoUnit.DAYS.between(fromDay, toDay) >= MAX_REPORT_DAYS) {
            throw new BusinessRuleException("REPORT_PERIOD_INVALID", "Pick a period of one day to a year",
                    Map.of("max", MAX_REPORT_DAYS));
        }
        Instant from = fromDay.atStartOfDay(clock.getZone()).toInstant();
        Instant to = toDay.plusDays(1).atStartOfDay(clock.getZone()).toInstant();
        Map<Long, Map<MovementKind, BigDecimal[]>> totals = new HashMap<>();
        for (Object[] row : movements.totalsBetween(from, to)) {
            totals.computeIfAbsent((Long) row[0], id -> new HashMap<>())
                    .put((MovementKind) row[1], new BigDecimal[] {(BigDecimal) row[2], (BigDecimal) row[3]});
        }
        List<Ingredient> listed = new ArrayList<>(ingredients.findByArchivedFalseOrderByNameAsc());
        ingredients.findAllById(totals.keySet()).stream().filter(Ingredient::isArchived).forEach(listed::add);
        Map<Long, BigDecimal> onHand = balances(listed.stream().map(Ingredient::getId).toList());
        List<StockReportResponse.Row> rows = listed.stream().map(ingredient -> {
            Map<MovementKind, BigDecimal[]> kinds = totals.getOrDefault(ingredient.getId(), Map.of());
            return new StockReportResponse.Row(ingredient.getId(), ingredient.getName(), ingredient.getUnit().name(),
                    quantity(kinds, MovementKind.SALE).negate(), value(ingredient, kinds, MovementKind.SALE).negate(),
                    quantity(kinds, MovementKind.WASTE).negate(), value(ingredient, kinds, MovementKind.WASTE).negate(),
                    quantity(kinds, MovementKind.DELIVERY), value(ingredient, kinds, MovementKind.DELIVERY),
                    quantity(kinds, MovementKind.COUNT), value(ingredient, kinds, MovementKind.COUNT),
                    balance(onHand, ingredient.getId()));
        }).toList();
        return new StockReportResponse(from, to, rows,
                sum(rows, StockReportResponse.Row::soldCost), sum(rows, StockReportResponse.Row::wasteCost),
                sum(rows, StockReportResponse.Row::deliveredCost), sum(rows, StockReportResponse.Row::varianceCost));
    }

    private static BigDecimal quantity(Map<MovementKind, BigDecimal[]> kinds, MovementKind kind) {
        BigDecimal[] totals = kinds.get(kind);
        return totals == null ? BigDecimal.ZERO.setScale(QuantityUnit.SCALE) : totals[0].setScale(QuantityUnit.SCALE, RoundingMode.HALF_UP);
    }

    /** Movements store the price per bulk unit (kilogram): divide back down to what the base quantity was worth. */
    private static BigDecimal value(Ingredient ingredient, Map<MovementKind, BigDecimal[]> kinds, MovementKind kind) {
        BigDecimal[] totals = kinds.get(kind);
        return totals == null ? BigDecimal.ZERO.setScale(2)
                : totals[1].divide(ingredient.getUnit().perBulkUnit(), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(List<StockReportResponse.Row> rows, Function<StockReportResponse.Row, BigDecimal> field) {
        return rows.stream().map(field).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    // ------------------------------------------------------------------------------------------- helpers

    Map<Long, BigDecimal> balances(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return movements.balancesOf(ids).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (BigDecimal) row[1]));
    }

    private static BigDecimal balance(Map<Long, BigDecimal> balances, Long id) {
        return balances.getOrDefault(id, BigDecimal.ZERO).setScale(QuantityUnit.SCALE, RoundingMode.HALF_UP);
    }

    Ingredient find(Long id) {
        return ingredients.findById(id).filter(ingredient -> !ingredient.isArchived())
                .orElseThrow(() -> new ResourceNotFoundException("ingredient", id));
    }

    IngredientResponse response(Ingredient ingredient, BigDecimal balance) {
        BigDecimal onHand = balance.setScale(QuantityUnit.SCALE, RoundingMode.HALF_UP);
        return new IngredientResponse(ingredient.getId(), ingredient.getName(), ingredient.getUnit(),
                ingredient.getUnit().bulkUnit(), ingredient.getCost(), ingredient.getLowStockLevel(),
                ingredient.isTrackStock(), onHand, ingredient.valueOf(onHand.max(BigDecimal.ZERO)).setScale(2, RoundingMode.HALF_UP),
                ingredient.isLow(onHand), ingredient.isOut(onHand));
    }

    private void requireUniqueName(String name, Long id) {
        if (ingredients.existsByNameIgnoreCaseAndArchivedFalseAndIdNot(name.strip(), id)) {
            throw new BusinessRuleException("INGREDIENT_NAME_TAKEN", "There is already an ingredient called " + name.strip(),
                    Map.of("name", name.strip()));
        }
    }

    private static void requirePositive(BigDecimal quantity) {
        if (quantity.signum() <= 0) {
            throw new BusinessRuleException("QUANTITY_REQUIRED", "Enter how much");
        }
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
