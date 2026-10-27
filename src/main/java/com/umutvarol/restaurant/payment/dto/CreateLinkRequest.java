package com.umutvarol.restaurant.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;

/** @param amount a fixed share for one guest; empty lets the guest pay whatever is still due */
public record CreateLinkRequest(@DecimalMin(value = "0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount) {
}
