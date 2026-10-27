package com.umutvarol.restaurant.payment.dto;

import java.math.BigDecimal;

/** The simulated provider's payment page: what is being paid and where to return to. */
public record SimulatedCheckoutResponse(String token, BigDecimal amount, String description, String callbackUrl) {
}
