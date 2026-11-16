package com.umutvarol.restaurant.push;

import com.umutvarol.restaurant.business.BusinessContext;
import com.umutvarol.restaurant.common.error.BusinessRuleException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Who wants to be told of what, and telling them. Messages are encrypted for each device (RFC 8291) and signed with
 * the server's VAPID key (RFC 8292); they are sent after the change is saved, on their own threads, so a slow push
 * service never holds up an order. A device the push service no longer knows (404, 410) is forgotten; one that keeps
 * failing is dropped after a few tries. Only the known push services of browsers are ever contacted.
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);
    private static final List<String> PUSH_HOSTS = List.of("fcm.googleapis.com", "updates.push.services.mozilla.com");
    private static final List<String> PUSH_SUFFIXES = List.of(".push.apple.com", ".notify.windows.com");
    private static final int MAX_FAILURES = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What a device sends when it subscribes (the browser's PushSubscription, plus what it wants and its language). */
    public record Subscribe(String endpoint, String p256dh, String auth, Set<PushTopic> topics, String lang) {
    }

    /** One device of the signed-in person, as they see it. */
    public record Device(String endpoint, Set<PushTopic> topics, String lang) {
    }

    private final PushSubscriptionRepository subscriptions;
    private final VapidKeys vapid;
    private final PushTransport transport;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Clock clock;
    private final TransactionTemplate tx;
    private final ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor();
    private final FcmSender fcm;

    PushService(PushSubscriptionRepository subscriptions, VapidKeys vapid, PushTransport transport,
                Clock clock, PlatformTransactionManager transactions, FcmSender fcm) {
        this.fcm = fcm;
        this.subscriptions = subscriptions;
        this.vapid = vapid;
        this.transport = transport;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    public String publicKey() {
        return vapid.publicKey();
    }

    /** Whether the store apps can be told too (a Firebase project is set up). */
    public boolean nativeEnabled() {
        return fcm.enabled();
    }

    /** Saves (or updates) a store app on this phone, by its Firebase token. */
    public Device subscribeNative(String token, Set<PushTopic> topics, String requestedLang, String username, @Nullable Long branchId) {
        if (!fcm.enabled()) {
            throw new BusinessRuleException("PUSH_NATIVE_UNAVAILABLE", "This server has no Firebase project for the store apps");
        }
        if (token == null || !token.matches("[A-Za-z0-9_:.\\-]{20,900}") || topics == null) {
            throw new BusinessRuleException("PUSH_SUBSCRIPTION_INVALID", "This is not a store app's notification token");
        }
        String endpoint = "fcm:" + token;
        String lang = PushMessages.supports(requestedLang) ? requestedLang : "en";
        Long business = BusinessContext.current();
        BusinessContext.acrossAll(() -> tx.execute(status -> {
            subscriptions.findByEndpoint(endpoint)
                    .filter(other -> business != null && !other.getBusinessId().equals(business))
                    .ifPresent(subscriptions::delete);
            return null;
        }));
        return tx.execute(status -> {
            PushSubscription own = subscriptions.findByEndpoint(endpoint).orElseGet(() -> new PushSubscription(endpoint, "FCM", clock.instant()));
            own.set(username, branchId, null, null, topics, lang);
            subscriptions.save(own);
            return new Device(own.getEndpoint(), own.getTopics(), own.getLang());
        });
    }

    /** Saves (or updates) this device for the signed-in person. */
    public Device subscribe(Subscribe request, String username, @Nullable Long branchId) {
        URI endpoint = requirePushService(request.endpoint());
        byte[] key = decode(request.p256dh(), 65);
        byte[] secret = decode(request.auth(), 16);
        if (key[0] != 4 || request.topics() == null) {
            throw new BusinessRuleException("PUSH_SUBSCRIPTION_INVALID", "This is not a browser push subscription");
        }
        String lang = PushMessages.supports(request.lang()) ? request.lang() : "en";
        Long business = BusinessContext.current();
        // the same browser may have subscribed for another business before: it is this one's now
        BusinessContext.acrossAll(() -> tx.execute(status -> {
            subscriptions.findByEndpoint(endpoint.toString())
                    .filter(other -> business != null && !other.getBusinessId().equals(business))
                    .ifPresent(subscriptions::delete);
            return null;
        }));
        return tx.execute(status -> {
            PushSubscription own = subscriptions.findByEndpoint(endpoint.toString())
                    .orElseGet(() -> new PushSubscription(endpoint.toString(), clock.instant()));
            own.set(username, branchId, b64(key), b64(secret), request.topics(), lang);
            subscriptions.save(own);
            return new Device(own.getEndpoint(), own.getTopics(), own.getLang());
        });
    }

    /** Forgets a device, only if it is the signed-in person's. */
    public void unsubscribe(String endpoint, String username) {
        tx.executeWithoutResult(status -> subscriptions.findByEndpoint(endpoint)
                .filter(own -> own.getUsername().equals(username)).ifPresent(subscriptions::delete));
    }

    public List<Device> devices(String username) {
        return tx.execute(status -> subscriptions.findByUsername(username).stream()
                .map(own -> new Device(own.getEndpoint(), own.getTopics(), own.getLang())).toList());
    }

    /** Sends a test message to the signed-in person's devices; how many there are. */
    public int test(String username) {
        List<PushSubscription> own = tx.execute(status -> subscriptions.findByUsername(username));
        Long business = BusinessContext.current();
        own.forEach(device -> send(business, device, PushMessages.test(device.getLang()), "#/settings", "sofra-test"));
        return own.size();
    }

    /**
     * Tells every device of this business that asked for {@code topic} and works in {@code branchId} (or in all
     * branches). {@code text} writes the message in each device's language.
     */
    void notify(PushTopic topic, @Nullable Long branchId, java.util.function.Function<String, PushMessages.Text> text,
                String url, String tag) {
        Long business = BusinessContext.current();
        List<PushSubscription> targets = tx.execute(status -> subscriptions.findAll().stream()
                .filter(device -> device.getTopics().contains(topic))
                .filter(device -> device.getBranchId() == null || branchId == null || device.getBranchId().equals(branchId))
                .toList());
        targets.forEach(device -> send(business, device, text.apply(device.getLang()), url, tag));
    }

    private void send(@Nullable Long business, PushSubscription device, PushMessages.Text text, String url, String tag) {
        Long id = device.getId();
        String fcmToken = device.fcmToken();
        senders.submit(() -> {
            int status;
            try {
                if (fcmToken != null) {
                    status = fcm.send(fcmToken, text.title(), text.body(), url, tag);
                    if (status == 0) {
                        return; // not set up, or Firebase not reached: nothing to learn about the device
                    }
                } else {
                Map<String, Object> message = new LinkedHashMap<>();
                message.put("title", text.title());
                message.put("body", text.body());
                message.put("url", url);
                message.put("tag", tag);
                byte[] payload = JSON.writeValueAsBytes(message);
                byte[] salt = new byte[16];
                RANDOM.nextBytes(salt);
                byte[] body = WebPushCrypto.encrypt(payload, Base64.getUrlDecoder().decode(device.getP256dh()),
                        Base64.getUrlDecoder().decode(device.getAuth()), WebPushCrypto.newKeyPair(), salt);
                URI endpoint = URI.create(device.getEndpoint());
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("TTL", "3600");
                headers.put("Urgency", "high");
                headers.put("Content-Encoding", "aes128gcm");
                headers.put("Content-Type", "application/octet-stream");
                headers.put("Authorization", vapid.authorization(endpoint));
                status = transport.post(endpoint, headers, body);
                }
            } catch (Exception ex) {
                log.warn("Push: a message could not be prepared: {}", ex.getMessage());
                return;
            }
            Runnable record = () -> tx.executeWithoutResult(st -> subscriptions.findById(id).ifPresent(own -> {
                if (status >= 200 && status < 300) {
                    own.delivered(clock.instant());
                } else if (status == 404 || status == 410 || own.failed() >= MAX_FAILURES) {
                    subscriptions.delete(own); // the browser unsubscribed, or the address keeps failing
                }
            }));
            if (business == null) {
                record.run();
            } else {
                BusinessContext.run(business, record);
            }
        });
    }

    /** A push address must belong to a browser's push service: nothing else is ever contacted. */
    static URI requirePushService(String value) {
        try {
            URI url = URI.create(value);
            String host = Optional.ofNullable(url.getHost()).orElse("").toLowerCase(Locale.ROOT);
            boolean known = PUSH_HOSTS.contains(host) || PUSH_SUFFIXES.stream().anyMatch(host::endsWith);
            if ("https".equals(url.getScheme()) && known && url.getPort() == -1 && url.getUserInfo() == null && value.length() <= 1000) {
                return url;
            }
        } catch (IllegalArgumentException ignored) {
            // falls through to the refusal
        }
        throw new BusinessRuleException("PUSH_SUBSCRIPTION_INVALID", "This is not a browser push address");
    }

    private static byte[] decode(String value, int length) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(value == null ? "" : value.replace('+', '-').replace('/', '_').replace("=", ""));
            if (bytes.length == length) {
                return bytes;
            }
        } catch (IllegalArgumentException ignored) {
            // falls through to the refusal
        }
        throw new BusinessRuleException("PUSH_SUBSCRIPTION_INVALID", "This is not a browser push subscription");
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
