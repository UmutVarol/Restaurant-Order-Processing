package com.umutvarol.restaurant.payment;

import com.umutvarol.restaurant.business.BusinessContext;
import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.payment.gateway.OnlinePaymentGateway;
import com.umutvarol.restaurant.security.totp.SecretBox;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A business connects its own iyzico merchant account, so guests' online payments go to it (Sofra Cloud). The keys
 * are sealed before they are stored and never shown again; only their last characters are.
 */
@Service
@Transactional
public class PaymentSettingsService {

    private static final Logger audit = LoggerFactory.getLogger("payment.audit");

    /** What the owner sees: never a key, only enough to recognise the account. */
    public record View(@Nullable String provider, boolean configured, @Nullable String keyHint, boolean sandbox,
                       @Nullable Instant updatedAt, @Nullable String updatedBy, @Nullable String serverProvider,
                       boolean encryptionReady) {
    }

    private final PaymentSettingsRepository settings;
    private final BusinessGateways gateways;
    private final OnlinePayments online;
    private final SecretBox box;
    private final Clock clock;

    PaymentSettingsService(PaymentSettingsRepository settings, BusinessGateways gateways, OnlinePayments online,
                           SecretBox box, Clock clock) {
        this.settings = settings;
        this.gateways = gateways;
        this.online = online;
        this.box = box;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public View view() {
        Optional<PaymentSettings> own = settings.findById(business());
        return new View(own.map(PaymentSettings::getProvider).orElse(null), own.isPresent(),
                own.map(PaymentSettings::getKeyHint).orElse(null), own.map(PaymentSettings::isSandbox).orElse(true),
                own.map(PaymentSettings::getUpdatedAt).orElse(null), own.map(PaymentSettings::getUpdatedBy).orElse(null),
                online.serverGateway().map(OnlinePaymentGateway::provider).orElse(null), box.available());
    }

    public View save(String apiKey, String secretKey, boolean sandbox, String actor) {
        if (!box.available()) {
            throw new BusinessRuleException("ENCRYPTION_KEY_MISSING",
                    "The server has no encryption key, so payment keys cannot be stored safely");
        }
        String api = clean(apiKey);
        String secret = clean(secretKey);
        long business = business();
        PaymentSettings own = settings.findById(business).orElseGet(() -> new PaymentSettings(business));
        own.change(box.seal(api.getBytes(StandardCharsets.UTF_8)), box.seal(secret.getBytes(StandardCharsets.UTF_8)),
                api.substring(Math.max(0, api.length() - 4)), sandbox, clock.instant(), actor);
        settings.save(own);
        audit.info("payment.settings_changed provider={} sandbox={} by={}", own.getProvider(), sandbox, actor);
        forgetAfterCommit(business);
        return view();
    }

    public View remove(String actor) {
        long business = business();
        settings.findById(business).ifPresent(own -> {
            settings.delete(own);
            audit.info("payment.settings_removed provider={} by={}", own.getProvider(), actor);
        });
        forgetAfterCommit(business);
        return view();
    }

    private void forgetAfterCommit(long business) {
        gateways.forget(business);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    gateways.forget(business);
                }
            });
        }
    }

    private static String clean(String key) {
        String value = key == null ? "" : key.strip();
        if (value.length() < 8 || value.length() > 200 || !value.matches("[\\x21-\\x7E]+")) {
            throw new BusinessRuleException("PAYMENT_KEY_INVALID", "Paste the key exactly as iyzico shows it");
        }
        return value;
    }

    private static long business() {
        return Optional.ofNullable(BusinessContext.current()).orElse(1L);
    }
}
