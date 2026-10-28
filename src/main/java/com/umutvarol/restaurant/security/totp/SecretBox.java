package com.umutvarol.restaurant.security.totp;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts small secrets for storage with AES-256-GCM: a random 96-bit nonce per value, stored in front of the
 * ciphertext, and a 128-bit tag that makes any change to the stored value fail to decrypt. A database leak alone then
 * does not reveal the managers' TOTP secrets.
 */
@Component
public class SecretBox {

    private static final Logger log = LoggerFactory.getLogger(SecretBox.class);
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    // Demo mode is read as a plain property: its settings live in the bootstrap package, which nothing depends on.
    SecretBox(TotpProperties properties, @Value("${restaurant.demo.enabled:true}") boolean demoMode) {
        byte[] raw = properties.encryptionKey().isBlank() ? null : Base64.getDecoder().decode(properties.encryptionKey().strip());
        if (raw == null && demoMode) {
            raw = new byte[32];
            RANDOM.nextBytes(raw);
            log.warn("TOTP_ENCRYPTION_KEY is not set: using a throwaway key, so two-step sign-in set up now is lost on restart.");
        }
        if (raw == null && properties.requiredForManagers()) {
            throw new IllegalStateException("Two-step sign-in is required for managers but TOTP_ENCRYPTION_KEY is not set. "
                    + "Generate one with `openssl rand -base64 32`.");
        }
        if (raw != null && raw.length != 32) {
            throw new IllegalStateException("TOTP_ENCRYPTION_KEY must be 32 bytes (256 bits), base64 encoded.");
        }
        this.key = raw == null ? null : new SecretKeySpec(raw, "AES");
    }

    public boolean available() {
        return key != null;
    }

    public String seal(byte[] plain) {
        requireKey();
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(plain);
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + sealed.length)
                    .put(nonce).put(sealed).array());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot encrypt", ex);
        }
    }

    public byte[] open(String sealed) {
        requireKey();
        try {
            byte[] data = Base64.getDecoder().decode(sealed);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, NONCE_BYTES));
            return cipher.doFinal(data, NONCE_BYTES, data.length - NONCE_BYTES);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("Cannot decrypt a stored secret: wrong TOTP_ENCRYPTION_KEY or tampered data", ex);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("TOTP_ENCRYPTION_KEY is not set");
        }
    }
}
