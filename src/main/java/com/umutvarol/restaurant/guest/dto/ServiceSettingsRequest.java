package com.umutvarol.restaurant.guest.dto;

import com.umutvarol.restaurant.guest.ServiceMode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * @param autoAcceptLimit     in {@link ServiceMode#MIXED}, carts above this total wait for a waiter; null for no limit
 * @param firstCartNeedsStaff in {@link ServiceMode#MIXED}, whether a waiter confirms the first cart of a table
 *                            nobody is seated at
 */
public record ServiceSettingsRequest(
        @NotNull ServiceMode mode,
        @DecimalMin("0.00") @DecimalMax("100000.00") @Digits(integer = 6, fraction = 2) BigDecimal autoAcceptLimit,
        @NotNull Boolean firstCartNeedsStaff) {
}
