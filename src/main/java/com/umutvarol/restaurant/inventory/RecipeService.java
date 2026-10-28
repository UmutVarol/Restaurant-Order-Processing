package com.umutvarol.restaurant.inventory;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.inventory.dto.DishCostResponse;
import com.umutvarol.restaurant.inventory.dto.RecipeLineRequest;
import com.umutvarol.restaurant.inventory.dto.RecipeRequest;
import com.umutvarol.restaurant.inventory.dto.RecipeResponse;
import com.umutvarol.restaurant.menu.MenuService;
import com.umutvarol.restaurant.menu.dto.MenuItemResponse;
import com.umutvarol.restaurant.menu.dto.OptionGroupResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Recipes of dishes and their options, and what each dish costs to make at today's prices. */
@Service
@Transactional
public class RecipeService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final RecipeLineRepository recipes;
    private final IngredientRepository ingredients;
    private final MenuService menu;
    private final StockService stock;

    RecipeService(RecipeLineRepository recipes, IngredientRepository ingredients, MenuService menu, StockService stock) {
        this.recipes = recipes;
        this.ingredients = ingredients;
        this.menu = menu;
        this.stock = stock;
    }

    @Transactional(readOnly = true)
    public RecipeResponse recipe(Long menuItemId) {
        return response(menu.get(menuItemId), recipes.findByMenuItemIdOrderByIdAsc(menuItemId));
    }

    /**
     * Replaces a dish's recipe. Option lines must name one of the dish's own option choices; a line that replaces
     * an ingredient must name one the dish itself uses.
     */
    public RecipeResponse replace(Long menuItemId, RecipeRequest request) {
        MenuItemResponse item = menu.get(menuItemId);
        Set<Long> referenced = new HashSet<>();
        for (RecipeLineRequest line : request.lines()) {
            referenced.add(line.ingredientId());
            if (line.replacesIngredientId() != null) {
                referenced.add(line.replacesIngredientId());
            }
        }
        Map<Long, Ingredient> known = ingredients.findAllById(referenced).stream()
                .filter(ingredient -> !ingredient.isArchived())
                .collect(Collectors.toMap(Ingredient::getId, Function.identity()));
        Set<Long> own = request.lines().stream().filter(line -> blank(line.optionGroup()) && blank(line.optionChoice()))
                .map(RecipeLineRequest::ingredientId).collect(Collectors.toSet());
        Set<String> seen = new HashSet<>();
        List<RecipeLine> lines = new ArrayList<>();
        for (RecipeLineRequest line : request.lines()) {
            Ingredient ingredient = require(known, line.ingredientId());
            boolean base = blank(line.optionGroup()) && blank(line.optionChoice());
            String[] option = base ? new String[] {null, null} : option(item, line.optionGroup(), line.optionChoice());
            if (base && line.replacesIngredientId() != null) {
                throw new BusinessRuleException("RECIPE_LINE_INVALID", "Only an option can replace an ingredient");
            }
            if (line.replacesIngredientId() != null) {
                require(known, line.replacesIngredientId());
                if (!own.contains(line.replacesIngredientId())) {
                    throw new BusinessRuleException("RECIPE_LINE_INVALID", "An option can only replace one of the dish's own ingredients");
                }
            }
            if (line.quantity().signum() == 0 && line.replacesIngredientId() == null) {
                throw new BusinessRuleException("RECIPE_QUANTITY_INVALID", "Enter how much of %s one portion uses"
                        .formatted(ingredient.getName()), Map.of("name", ingredient.getName()));
            }
            if (!seen.add(option[0] + "|" + option[1] + "|" + ingredient.getId())) {
                throw new BusinessRuleException("RECIPE_DUPLICATE_INGREDIENT", "%s is in the recipe twice"
                        .formatted(ingredient.getName()), Map.of("name", ingredient.getName()));
            }
            lines.add(new RecipeLine(menuItemId, option[0], option[1], ingredient.getId(), line.quantity(),
                    line.replacesIngredientId()));
        }
        recipes.deleteByMenuItemId(menuItemId);
        recipes.flush();
        recipes.saveAll(lines);
        stock.refreshDishes(List.of(menuItemId));
        return response(item, lines);
    }

    /** Cost and food cost percentage of every dish that has a recipe. */
    @Transactional(readOnly = true)
    public List<DishCostResponse> costs() {
        List<MenuItemResponse> items = menu.list(null, null, true);
        Map<Long, List<RecipeLine>> byDish = recipes.findByMenuItemIdIn(items.stream().map(MenuItemResponse::id).toList())
                .stream().collect(Collectors.groupingBy(RecipeLine::getMenuItemId));
        Map<Long, Ingredient> byId = ingredients.findAllById(byDish.values().stream().flatMap(List::stream)
                        .map(RecipeLine::getIngredientId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Ingredient::getId, Function.identity()));
        return items.stream().filter(item -> byDish.containsKey(item.id())).map(item -> {
            BigDecimal cost = portionCost(byDish.get(item.id()), byId);
            return new DishCostResponse(item.id(), cost, percent(cost, item.price()));
        }).toList();
    }

    private RecipeResponse response(MenuItemResponse item, List<RecipeLine> lines) {
        Map<Long, Ingredient> byId = ingredients.findAllById(lines.stream().map(RecipeLine::getIngredientId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Ingredient::getId, Function.identity()));
        List<RecipeResponse.Line> out = lines.stream().map(line -> {
            Ingredient ingredient = byId.get(line.getIngredientId());
            return new RecipeResponse.Line(line.getIngredientId(), ingredient.getName(), ingredient.getUnit().name(),
                    line.getQuantity(), line.getOptionGroup(), line.getOptionChoice(), line.getReplacesIngredientId(),
                    money(ingredient.valueOf(line.getQuantity())));
        }).toList();
        BigDecimal cost = portionCost(lines, byId);
        return new RecipeResponse(item.id(), item.name(), item.price(), out, cost,
                lines.isEmpty() ? null : percent(cost, item.price()));
    }

    /** One portion without options. */
    private static BigDecimal portionCost(List<RecipeLine> lines, Map<Long, Ingredient> byId) {
        return money(lines.stream().filter(RecipeLine::isBase)
                .map(line -> {
                    Ingredient ingredient = byId.get(line.getIngredientId());
                    return ingredient == null ? BigDecimal.ZERO : ingredient.valueOf(line.getQuantity());
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /** Prices on the menu are before tax, so the percentage is of what the restaurant keeps. */
    private static @Nullable BigDecimal percent(BigDecimal cost, BigDecimal price) {
        return price.signum() == 0 ? null : cost.multiply(HUNDRED).divide(price, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    /** The dish's own spelling of the option group and choice the line names, ignoring case. */
    private static String[] option(MenuItemResponse item, @Nullable String group, @Nullable String choice) {
        if (blank(group) || blank(choice)) {
            throw new BusinessRuleException("RECIPE_LINE_INVALID", "An option line needs both the group and the choice");
        }
        for (OptionGroupResponse candidate : item.optionGroups()) {
            if (candidate.name().equalsIgnoreCase(group.strip())) {
                for (OptionGroupResponse.Choice option : candidate.choices()) {
                    if (option.name().equalsIgnoreCase(choice.strip())) {
                        return new String[] {candidate.name(), option.name()};
                    }
                }
            }
        }
        throw new BusinessRuleException("RECIPE_OPTION_NOT_FOUND", "%s has no option %s: %s"
                .formatted(item.name(), group.strip(), choice.strip()), Map.of("group", group.strip(), "choice", choice.strip()));
    }

    private static Ingredient require(Map<Long, Ingredient> known, Long id) {
        Ingredient ingredient = known.get(id);
        if (ingredient == null) {
            throw new BusinessRuleException("INGREDIENT_NOT_FOUND", "Ingredient %d does not exist".formatted(id),
                    Map.of("id", id));
        }
        return ingredient;
    }

    private static boolean blank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
