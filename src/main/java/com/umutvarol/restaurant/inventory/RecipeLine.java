package com.umutvarol.restaurant.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * How much of an ingredient one portion of a dish uses. With an option it applies only when that choice was
 * picked, and may replace one of the dish's own ingredients.
 */
@Entity
@Table(name = "recipe_lines")
public class RecipeLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "menu_item_id", nullable = false)
    private Long menuItemId;

    @Column(name = "option_group", length = 40)
    private @Nullable String optionGroup;

    @Column(name = "option_choice", length = 40)
    private @Nullable String optionChoice;

    @Column(name = "ingredient_id", nullable = false)
    private Long ingredientId;

    /** In the ingredient's base unit. */
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(name = "replaces_ingredient_id")
    private @Nullable Long replacesIngredientId;

    protected RecipeLine() {
        // for JPA
    }

    RecipeLine(Long menuItemId, @Nullable String optionGroup, @Nullable String optionChoice, Long ingredientId,
               BigDecimal quantity, @Nullable Long replacesIngredientId) {
        this.menuItemId = menuItemId;
        this.optionGroup = optionGroup;
        this.optionChoice = optionChoice;
        this.ingredientId = ingredientId;
        this.quantity = quantity;
        this.replacesIngredientId = replacesIngredientId;
    }

    boolean isBase() {
        return optionGroup == null;
    }

    /** Options are matched by name, ignoring case: order lines keep the names, not the ids. */
    boolean appliesTo(String group, String choice) {
        return optionGroup != null && optionChoice != null
                && optionGroup.toLowerCase(Locale.ROOT).equals(group.toLowerCase(Locale.ROOT))
                && optionChoice.toLowerCase(Locale.ROOT).equals(choice.toLowerCase(Locale.ROOT));
    }

    public Long getId() {
        return id;
    }

    public Long getMenuItemId() {
        return menuItemId;
    }

    public @Nullable String getOptionGroup() {
        return optionGroup;
    }

    public @Nullable String getOptionChoice() {
        return optionChoice;
    }

    public Long getIngredientId() {
        return ingredientId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public @Nullable Long getReplacesIngredientId() {
        return replacesIngredientId;
    }
}
