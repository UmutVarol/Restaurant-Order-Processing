package com.umutvarol.restaurant.payment.gateway;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * iyzico credentials and the merchant details its API requires for every checkout. Keys come from the iyzico
 * merchant panel (sandbox keys from sandbox-merchant.iyzipay.com); keep them in environment variables.
 *
 * @param apiKey          {@code IYZICO_API_KEY}
 * @param secretKey       {@code IYZICO_SECRET_KEY}; also used to verify response signatures
 * @param baseUrl         {@code https://sandbox-api.iyzipay.com} for testing, {@code https://api.iyzipay.com} live
 * @param buyerEmail      iyzico requires a buyer e-mail; restaurant guests rarely give one, so a fixed address is sent
 * @param buyerIdentityNumber iyzico requires an identity number field; a placeholder is sent for walk-in guests
 * @param city            merchant city, sent as the billing and shipping city
 * @param country         merchant country
 * @param address         merchant address, sent as the billing and shipping address
 */
@ConfigurationProperties(prefix = "restaurant.payments.iyzico")
public record IyzicoProperties(
        String apiKey,
        String secretKey,
        @DefaultValue("https://sandbox-api.iyzipay.com") URI baseUrl,
        @DefaultValue("guest@sofra.example") String buyerEmail,
        @DefaultValue("11111111111") String buyerIdentityNumber,
        @DefaultValue("Istanbul") String city,
        @DefaultValue("Turkey") String country,
        @DefaultValue("Moda Cad. No:1, Kadikoy") String address) {

    /** Keys never end up in logs or error pages, even if the settings object is printed. */
    @Override
    public String toString() {
        return "IyzicoProperties[apiKey=%s, secretKey=%s, baseUrl=%s]".formatted(mask(apiKey), mask(secretKey), baseUrl);
    }

    private static String mask(String key) {
        return key == null || key.isBlank() ? "<not set>" : "****";
    }
}
