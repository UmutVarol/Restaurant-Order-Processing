package com.umutvarol.restaurant.push;

import com.umutvarol.restaurant.business.BusinessContext;
import com.umutvarol.restaurant.security.totp.SecretBox;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The server's VAPID identity (RFC 8292): push services accept messages signed with it. Taken from the environment
 * when given ({@code PUSH_VAPID_PUBLIC_KEY}, {@code PUSH_VAPID_PRIVATE_KEY}), otherwise made once and kept in the
 * database, so every instance of the server signs alike and existing subscriptions keep working after a restart.
 */
@Component
class VapidKeys {

    private static final Logger log = LoggerFactory.getLogger(VapidKeys.class);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder UNB64 = Base64.getUrlDecoder();

    private final PushKeyRepository keys;
    private final SecretBox box;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final String configuredPublic;
    private final String configuredPrivate;
    private final String contact;
    private volatile KeyPair pair;

    VapidKeys(PushKeyRepository keys, SecretBox box, Clock clock, PlatformTransactionManager transactions,
              @Value("${restaurant.push.vapid-public-key:}") String configuredPublic,
              @Value("${restaurant.push.vapid-private-key:}") String configuredPrivate,
              @Value("${restaurant.push.contact:https://sofra.app}") String contact) {
        this.keys = keys;
        this.box = box;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.configuredPublic = configuredPublic;
        this.configuredPrivate = configuredPrivate;
        this.contact = contact;
    }

    /** The public key browsers subscribe with ({@code applicationServerKey}), base64url. */
    String publicKey() {
        return B64.encodeToString(WebPushCrypto.raw((ECPublicKey) pair().getPublic()));
    }

    /** The Authorization header for one push service: {@code vapid t=<JWT>, k=<public key>}, valid for 12 hours. */
    String authorization(URI endpoint) {
        String audience = endpoint.getScheme() + "://" + endpoint.getHost() + (endpoint.getPort() == -1 ? "" : ":" + endpoint.getPort());
        long expires = clock.instant().plus(Duration.ofHours(12)).getEpochSecond();
        String header = B64.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = B64.encodeToString("{\"aud\":\"%s\",\"exp\":%d,\"sub\":\"%s\"}".formatted(audience, expires, contact)
                .getBytes(StandardCharsets.UTF_8));
        try {
            // P1363: the raw r||s form JWTs use, not DER
            Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
            signer.initSign((ECPrivateKey) pair().getPrivate());
            signer.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
            String jwt = header + "." + claims + "." + B64.encodeToString(signer.sign());
            return "vapid t=" + jwt + ", k=" + publicKey();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private KeyPair pair() {
        KeyPair current = pair;
        if (current == null) {
            synchronized (this) {
                if (pair == null) {
                    pair = load();
                }
                current = pair;
            }
        }
        return current;
    }

    private KeyPair load() {
        try {
            if (!configuredPublic.isBlank() && !configuredPrivate.isBlank()) {
                return WebPushCrypto.keyPair(UNB64.decode(configuredPublic.strip()), UNB64.decode(configuredPrivate.strip()));
            }
            PushKey stored = BusinessContext.acrossAll(() -> tx.execute(status -> keys.findById(PushKey.ID).orElse(null)));
            if (stored == null) {
                stored = create();
            }
            return WebPushCrypto.keyPair(UNB64.decode(stored.getPublicKey()), UNB64.decode(open(stored.getPrivateKey())));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("The VAPID keys cannot be read", ex);
        }
    }

    private PushKey create() {
        KeyPair fresh = WebPushCrypto.newKeyPair();
        String publicKey = B64.encodeToString(WebPushCrypto.raw((ECPublicKey) fresh.getPublic()));
        String privateKey = B64.encodeToString(WebPushCrypto.raw((ECPrivateKey) fresh.getPrivate()));
        String kept = box.available() ? "sealed:" + box.seal(privateKey.getBytes(StandardCharsets.UTF_8)) : "plain:" + privateKey;
        try {
            PushKey created = BusinessContext.acrossAll(() -> tx.execute(status -> keys.saveAndFlush(new PushKey(publicKey, kept, clock.instant()))));
            log.info("Push: made the server's VAPID key pair");
            return created;
        } catch (DataIntegrityViolationException raced) {
            // another instance made them first: use theirs
            return BusinessContext.acrossAll(() -> tx.execute(status -> keys.findById(PushKey.ID).orElseThrow()));
        }
    }

    private String open(String kept) {
        if (kept.startsWith("sealed:")) {
            return new String(box.open(kept.substring("sealed:".length())), StandardCharsets.UTF_8);
        }
        return kept.substring("plain:".length());
    }
}
