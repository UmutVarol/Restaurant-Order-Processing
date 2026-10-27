package com.umutvarol.restaurant.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * A guest starting to pay.
 *
 * @param amount ignored when the link has a fixed share; otherwise defaults to everything still due
 * @param name   shown on the provider's page and the bank statement; optional
 */
public record PublicCheckoutRequest(
        @DecimalMin(value = "0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @DecimalMin(value = "0.00") @Digits(integer = 10, fraction = 2) BigDecimal tip,
        @Size(max = 60) String name) {
}
