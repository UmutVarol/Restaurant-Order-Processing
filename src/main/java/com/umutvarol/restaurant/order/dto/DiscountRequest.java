package com.umutvarol.restaurant.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/** A manual discount as a percentage or an amount; neither removes the discount. */
public record DiscountRequest(
        @DecimalMin("0.01") @DecimalMax("100") @Digits(integer = 3, fraction = 2) @Nullable BigDecimal percent,
        @DecimalMin("0.01") @DecimalMax("100000") @Digits(integer = 6, fraction = 2) @Nullable BigDecimal amount,
        @NotBlank @Size(max = 140) String reason,
        @Valid @Nullable ManagerApproval approval) {
}
