package com.umutvarol.restaurant.payment.dto;

/** Where to send the guest: the provider's secure payment page. */
public record CheckoutResponse(String paymentPageUrl) {
}
