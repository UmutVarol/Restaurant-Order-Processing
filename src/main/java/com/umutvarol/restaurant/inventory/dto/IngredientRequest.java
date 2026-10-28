package com.umutvarol.restaurant.inventory.dto;

import com.umutvarol.restaurant.inventory.StockUnit;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * @param cost          per kilogram, litre or piece
 * @param lowStockLevel in base units (grams, millilitres, pieces); empty for no warning
 * @param trackStock    false to cost the ingredient without counting it (salt, oil)
 */
public record IngredientRequest(
        @NotBlank @Size(max = 60) String name,
        @NotNull StockUnit unit,
        @NotNull @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal cost,
        @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal lowStockLevel,
        Boolean trackStock) {

    public boolean trackStockOrDefault() {
        return trackStock == null || trackStock;
    }
}
