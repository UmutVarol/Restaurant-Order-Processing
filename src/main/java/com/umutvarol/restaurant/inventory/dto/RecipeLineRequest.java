package com.umutvarol.restaurant.inventory.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * One ingredient of a dish, in the ingredient's base unit. With {@code optionGroup} and {@code optionChoice} it
 * applies only when that choice is picked, and can replace one of the dish's own ingredients.
 */
public record RecipeLineRequest(
        @NotNull Long ingredientId,
        @NotNull @DecimalMin("0") @Digits(integer = 9, fraction = 3) BigDecimal quantity,
        @Size(max = 40) String optionGroup,
        @Size(max = 40) String optionChoice,
        Long replacesIngredientId) {
}
