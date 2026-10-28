package com.umutvarol.restaurant.security.totp;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords as authenticator apps compute them (RFC 6238 over RFC 4226): HMAC-SHA1, 30-second
 * steps, six digits. One step of clock drift either way is accepted, and a step at or before the last one used is
 * refused, so an observed code cannot be replayed.
 */
public final class Totp {

    public static final int DIGITS = 6;
    public static final long STEP_SECONDS = 30;
    private static final int DRIFT_STEPS = 1;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /** 160 random bits, the size RFC 4226 recommends for HMAC-SHA1. */
    public static byte[] newSecret() {
        byte[] secret = new byte[20];
        RANDOM.nextBytes(secret);
        return secret;
    }

    public static long step(Instant at) {
        return Math.floorDiv(at.getEpochSecond(), STEP_SECONDS);
    }

    public static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%0" + DIGITS + "d", binary % 1_000_000);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA1 is not available", ex);
        }
    }

    /**
     * @return the step the code belongs to, if it is valid now and newer than {@code lastUsedStep}
     */
    public static OptionalLong verify(byte[] secret, String code, Instant now, long lastUsedStep) {
        if (code == null || !code.matches("\\d{" + DIGITS + "}")) {
            return OptionalLong.empty();
        }
        long current = step(now);
        for (long step = current - DRIFT_STEPS; step <= current + DRIFT_STEPS; step++) {
            boolean matches = MessageDigest.isEqual(code(secret, step).getBytes(StandardCharsets.US_ASCII), code.getBytes(StandardCharsets.US_ASCII));
            if (matches && step > lastUsedStep) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    /** The {@code otpauth://} link authenticator apps read from the enrolment QR code. */
    public static String uri(String issuer, String account, byte[] secret) {
        String label = encode(issuer) + ":" + encode(account);
        return "otpauth://totp/" + label + "?secret=" + Base32.encode(secret) + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
