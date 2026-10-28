package com.umutvarol.restaurant.inventory.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * @param cost            one portion with no options, at today's prices
 * @param foodCostPercent cost as a share of the price before tax; null without a recipe
 */
public record RecipeResponse(Long menuItemId, String name, BigDecimal price, List<Line> lines, BigDecimal cost,
                             BigDecimal foodCostPercent) {

    public record Line(Long ingredientId, String ingredient, String unit, BigDecimal quantity, String optionGroup,
                       String optionChoice, Long replacesIngredientId, BigDecimal cost) {
    }
}
