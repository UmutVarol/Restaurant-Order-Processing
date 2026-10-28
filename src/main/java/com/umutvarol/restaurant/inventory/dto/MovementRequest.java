package com.umutvarol.restaurant.inventory.dto;

import com.umutvarol.restaurant.inventory.MovementKind;
import com.umutvarol.restaurant.inventory.QuantityUnit;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * A delivery, waste or count, typed in any unit that fits the ingredient (kilograms of a gram-counted ingredient).
 *
 * @param quantity for a count, what is on the shelf; otherwise how much came in or was thrown away
 * @param unitCost deliveries only: the price per kilogram, litre or piece paid; it becomes the ingredient's cost
 */
public record MovementRequest(
        @NotNull MovementKind kind,
        @NotNull @DecimalMin("0") @DecimalMax("1000000") @Digits(integer = 7, fraction = 3) BigDecimal quantity,
        @NotNull QuantityUnit unit,
        @Size(max = 200) String reason,
        @Size(max = 80) String supplier,
        @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal unitCost) {
}
