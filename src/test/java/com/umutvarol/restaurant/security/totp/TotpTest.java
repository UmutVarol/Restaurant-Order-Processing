package com.umutvarol.restaurant.security.totp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class TotpTest {

    /** The SHA-1 seed of RFC 6238 appendix B. */
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesTheRfc6238TestVectors() {
        // appendix B lists 8 digits; authenticator apps use the last 6
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(59)))).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1111111109)))).isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1234567890)))).isEqualTo("005924");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(2000000000)))).isEqualTo("279037");
    }

    @Test
    void acceptsOneStepOfDriftAndNeverTheSameStepTwice() {
        Instant now = Instant.ofEpochSecond(1_700_000_000);
        long step = Totp.step(now);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 1), now, 0)).hasValue(step - 1);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step + 1), now, 0)).hasValue(step + 1);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 2), now, 0)).isEmpty();
        // replay: the step was already used
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step), now, step)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, "12345", now, 0)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, "abcdef", now, 0)).isEmpty();
    }

    @Test
    void base32RoundTripsAndMatchesRfc4648() {
        assertThat(Base32.encode("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
        byte[] secret = Totp.newSecret();
        assertThat(Base32.decode(Base32.encode(secret))).isEqualTo(secret);
        assertThat(Totp.uri("Sofra", "elif", RFC_SECRET))
                .startsWith("otpauth://totp/Sofra:elif?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Sofra");
    }

    @Test
    void secretsAreEncryptedAndTamperingIsDetected() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        SecretBox box = newBox(key);
        String sealed = box.seal(RFC_SECRET);
        assertThat(sealed).doesNotContain("MTIzNDU2"); // not the plain base64 of the secret
        assertThat(box.seal(RFC_SECRET)).isNotEqualTo(sealed); // a fresh nonce each time
        assertThat(box.open(sealed)).isEqualTo(RFC_SECRET);

        byte[] raw = Base64.getDecoder().decode(sealed);
        raw[raw.length - 1] ^= 1;
        assertThatThrownBy(() -> box.open(Base64.getEncoder().encodeToString(raw))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> newBox(Base64.getEncoder().encodeToString(new byte[16])).seal(RFC_SECRET))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToStartWhenRequiredWithoutAKey() {
        assertThatThrownBy(() -> new SecretBox(new TotpProperties("", true, "Sofra"), false))
                .hasMessageContaining("TOTP_ENCRYPTION_KEY");
        assertThat(new SecretBox(new TotpProperties("", false, "Sofra"), false).available()).isFalse();
        assertThat(new SecretBox(new TotpProperties("", false, "Sofra"), true).available()).isTrue();
    }

    private static SecretBox newBox(String key) {
        return new SecretBox(new TotpProperties(key, false, "Sofra"), false);
    }
}
