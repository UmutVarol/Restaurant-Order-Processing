package com.umutvarol.restaurant.payment;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * The address guests reach the app on, for QR links and provider callbacks. Taken from configuration when set,
 * otherwise from the current request (which honours forwarded headers when the app trusts its proxy).
 */
@Component
class PublicUrls {

    private final PaymentProperties properties;

    PublicUrls(PaymentProperties properties) {
        this.properties = properties;
    }

    String baseUrl() {
        String configured = properties.publicBaseUrl();
        if (configured != null && !configured.isBlank()) {
            return configured.strip().replaceAll("/+$", "");
        }
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }
}
