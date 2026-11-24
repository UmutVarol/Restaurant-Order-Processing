package com.umutvarol.restaurant.integration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.umutvarol.restaurant.business.Business;
import com.umutvarol.restaurant.business.BusinessContext;
import com.umutvarol.restaurant.business.BusinessProfile;
import com.umutvarol.restaurant.business.BusinessRepository;
import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.security.totp.SecretBox;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A business's own e-Arşiv integrator and delivery platforms (Sofra Cloud). Keys and webhook secrets are sealed
 * before they are stored and never shown again; only their last characters are. The fiscal and channel features
 * ask here first and fall back to the server's own settings on a restaurant's own server; on Sofra Cloud the
 * server's settings are never shared between businesses.
 */
@Service
@Transactional
public class IntegrationService {

    private static final Logger audit = LoggerFactory.getLogger("integration.audit");

    /** What the fiscal and channel features use: opened, for this business, as last saved. */
    public record Connection(IntegrationKind kind, @Nullable URI baseUrl, @Nullable String apiKey,
                             @Nullable String webhookSecret, long version) {

        public boolean hasApi() {
            return baseUrl != null && apiKey != null;
        }
    }

    /** What the owner sees of one connection: never a key or secret. */
    public record Entry(IntegrationKind kind, boolean inPlan, boolean configured, @Nullable String baseUrl,
                        @Nullable String keyHint, boolean webhookSecretSet, @Nullable String webhookPath,
                        @Nullable Instant updatedAt, @Nullable String updatedBy) {
    }

    public record View(List<Entry> entries, boolean encryptionReady, boolean cloud) {
    }

    private final IntegrationSettingRepository settings;
    private final BusinessRepository businesses;
    private final BusinessProfile profile;
    private final SecretBox box;
    private final Clock clock;
    private final boolean cloud;
    private final Cache<String, Optional<Connection>> connections = Caffeine.newBuilder()
            .maximumSize(20_000).expireAfterWrite(Duration.ofMinutes(10)).build();

    IntegrationService(IntegrationSettingRepository settings, BusinessRepository businesses, BusinessProfile profile,
                       SecretBox box, Clock clock, @Value("${restaurant.cloud.enabled:false}") boolean cloud) {
        this.settings = settings;
        this.businesses = businesses;
        this.profile = profile;
        this.box = box;
        this.clock = clock;
        this.cloud = cloud;
    }

    /** Whether this server is Sofra Cloud, where server-wide connections are never used for a business. */
    public boolean cloud() {
        return cloud;
    }

    /** The current business's own connection of this kind, if it made one. */
    @Transactional(readOnly = true)
    public Optional<Connection> connection(IntegrationKind kind) {
        long business = business();
        String key = business + ":" + kind;
        Optional<Connection> cached = connections.getIfPresent(key);
        if (cached == null) {
            // read outside the cache's lock: a database call inside it would pin a virtual thread (docs/LOAD_TEST.md)
            cached = settings.findByKind(kind).map(this::open);
            connections.put(key, cached);
        }
        return cached;
    }

    @Transactional(readOnly = true)
    public View view() {
        long business = business(); // read here: inside acrossAll there is no current business
        String slug = BusinessContext.acrossAll(() -> businesses.findById(business)).map(Business::getSlug).orElse(null);
        List<Entry> entries = Arrays.stream(IntegrationKind.values()).map(kind -> {
            Optional<IntegrationSetting> own = settings.findByKind(kind);
            return new Entry(kind, profile.includes(kind.feature()), own.isPresent(),
                    own.map(IntegrationSetting::getBaseUrl).orElse(null), own.map(IntegrationSetting::getKeyHint).orElse(null),
                    own.map(s -> s.getWebhookSecret() != null).orElse(false),
                    kind.platform() && slug != null ? "/api/v1/channels/%s/webhook/%s".formatted(kind.name().toLowerCase(Locale.ROOT), slug) : null,
                    own.map(IntegrationSetting::getUpdatedAt).orElse(null), own.map(IntegrationSetting::getUpdatedBy).orElse(null));
        }).toList();
        return new View(entries, box.available(), cloud);
    }

    /**
     * Saves a connection. A key or secret left empty keeps the one already saved. e-Arşiv needs the integrator's
     * address and key; a platform needs its webhook secret, and may add an API address and token for status updates.
     */
    public View save(IntegrationKind kind, @Nullable String baseUrl, @Nullable String apiKey, @Nullable String webhookSecret,
                     String actor) {
        if (!box.available()) {
            throw new BusinessRuleException("ENCRYPTION_KEY_MISSING",
                    "The server has no encryption key, so keys cannot be stored safely");
        }
        IntegrationSetting own = settings.findByKind(kind).orElseGet(() -> new IntegrationSetting(kind));
        String url = blank(baseUrl) ? null : OutboundUrls.requirePublicHttps(baseUrl).toString();
        String key = blank(apiKey) ? own.getApiKey() : box.seal(clean(apiKey).getBytes(StandardCharsets.UTF_8));
        String secret = blank(webhookSecret) ? own.getWebhookSecret() : box.seal(clean(webhookSecret).getBytes(StandardCharsets.UTF_8));
        if ((kind == IntegrationKind.E_ARSIV || kind == IntegrationKind.E_ADISYON) && (url == null || key == null)) {
            throw new BusinessRuleException("INTEGRATION_INCOMPLETE", "The integrator's address and API key are both needed");
        }
        if (kind.platform() && secret == null) {
            throw new BusinessRuleException("INTEGRATION_INCOMPLETE", "The platform's webhook secret is needed");
        }
        if (kind.platform() && (url == null) != (key == null)) {
            throw new BusinessRuleException("INTEGRATION_INCOMPLETE", "Give both the API address and its token, or neither");
        }
        String hint = !blank(apiKey) ? last4(apiKey) : !blank(webhookSecret) ? last4(webhookSecret) : own.getKeyHint();
        own.change(url, key, secret, hint, clock.instant(), actor);
        settings.save(own);
        audit.info("integration.saved kind={} api={} by={}", kind, url != null, actor);
        forgetAfterCommit();
        return view();
    }

    public View remove(IntegrationKind kind, String actor) {
        settings.findByKind(kind).ifPresent(own -> {
            settings.delete(own);
            audit.info("integration.removed kind={} by={}", kind, actor);
        });
        forgetAfterCommit();
        return view();
    }

    private Connection open(IntegrationSetting setting) {
        return new Connection(setting.getKind(),
                setting.getBaseUrl() == null ? null : URI.create(setting.getBaseUrl()),
                setting.getApiKey() == null ? null : new String(box.open(setting.getApiKey()), StandardCharsets.UTF_8),
                setting.getWebhookSecret() == null ? null : new String(box.open(setting.getWebhookSecret()), StandardCharsets.UTF_8),
                setting.getVersion());
    }

    private void forgetAfterCommit() {
        long business = business();
        Runnable forget = () -> Arrays.stream(IntegrationKind.values()).forEach(kind -> connections.invalidate(business + ":" + kind));
        forget.run();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    forget.run();
                }
            });
        }
    }

    private static boolean blank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    private static String clean(String value) {
        String v = value.strip();
        if (v.length() < 8 || v.length() > 300 || !v.matches("[\\x21-\\x7E]+")) {
            throw new BusinessRuleException("INTEGRATION_KEY_INVALID", "Paste the key exactly as the provider shows it");
        }
        return v;
    }

    private static String last4(String value) {
        String v = value.strip();
        return v.substring(Math.max(0, v.length() - 4));
    }

    private static long business() {
        return Optional.ofNullable(BusinessContext.current()).orElse(1L);
    }
}
