package com.umutvarol.restaurant.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** A new payment link. The token is shown only here: the server keeps just its hash. */
public record PaymentLinkResponse(String token, String url, BigDecimal amount, Instant expiresAt) {
}
