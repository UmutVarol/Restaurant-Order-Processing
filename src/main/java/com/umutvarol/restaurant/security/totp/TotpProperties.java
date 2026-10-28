package com.umutvarol.restaurant.security.totp;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Two-step sign-in for managers.
 *
 * @param encryptionKey       base64 of 32 random bytes ({@code openssl rand -base64 32}); encrypts the TOTP secrets
 *                            at rest. Without it two-step sign-in cannot be set up (demo mode makes a throwaway key)
 * @param requiredForManagers when true, managers must set up two-step sign-in before refunds, closing the day or
 *                            registering tills
 * @param issuer              the name authenticator apps show next to the code
 */
@Validated
@ConfigurationProperties(prefix = "restaurant.security.totp")
public record TotpProperties(
        @DefaultValue("") String encryptionKey,
        @DefaultValue("false") boolean requiredForManagers,
        @DefaultValue("Sofra") @NotBlank String issuer) {

    @Override
    public String toString() {
        return "TotpProperties[encryptionKey=" + (encryptionKey.isBlank() ? "" : "****") + ", requiredForManagers="
                + requiredForManagers + ", issuer=" + issuer + "]";
    }
}
