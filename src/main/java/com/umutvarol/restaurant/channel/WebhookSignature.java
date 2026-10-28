package com.umutvarol.restaurant.channel;

import com.umutvarol.restaurant.common.error.AccessRefusedException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * Checks a webhook was sent by the platform: {@code X-Sofra-Signature} is the hex HMAC-SHA256 of
 * {@code timestamp + "." + body} under the shared secret, compared in constant time, and {@code X-Sofra-Timestamp}
 * (Unix seconds) must be within the tolerance, so a captured delivery cannot be replayed later.
 */
final class WebhookSignature {

    static final String SIGNATURE_HEADER = "X-Sofra-Signature";
    static final String TIMESTAMP_HEADER = "X-Sofra-Timestamp";

    private WebhookSignature() {
    }

    static String sign(String secret, long timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is not available", ex);
        }
    }

    /** @throws AccessRefusedException {@code WEBHOOK_SIGNATURE_INVALID} or {@code WEBHOOK_EXPIRED} */
    static void verify(String secret, @Nullable String timestamp, @Nullable String signature, byte[] body, Instant now,
                       Duration tolerance) {
        long sentAt;
        try {
            sentAt = Long.parseLong(timestamp == null ? "" : timestamp.strip());
        } catch (NumberFormatException ex) {
            throw invalid();
        }
        if (signature == null || signature.length() != 64) {
            throw invalid();
        }
        byte[] expected = sign(secret, sentAt, body).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = signature.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw invalid();
        }
        if (Duration.between(Instant.ofEpochSecond(sentAt), now).abs().compareTo(tolerance) > 0) {
            throw new AccessRefusedException("WEBHOOK_EXPIRED", "The webhook timestamp is outside the allowed window");
        }
    }

    private static AccessRefusedException invalid() {
        return new AccessRefusedException("WEBHOOK_SIGNATURE_INVALID", "The webhook signature is not valid");
    }
}
