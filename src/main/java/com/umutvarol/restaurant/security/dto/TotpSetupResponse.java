package com.umutvarol.restaurant.security.dto;

/** The new secret, shown once: as text to type in, and as the link the QR code carries. */
public record TotpSetupResponse(String secret, String otpauthUri) {

    @Override
    public String toString() {
        return "TotpSetupResponse[secret=***]";
    }
}
