package com.umutvarol.restaurant.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** @param countedCash what is actually in the drawer, counted by hand */
public record CloseDayRequest(
        @NotNull LocalDate date,
        @NotNull @DecimalMin(value = "0.00") @Digits(integer = 10, fraction = 2) BigDecimal countedCash,
        @Size(max = 255) String note) {
}
