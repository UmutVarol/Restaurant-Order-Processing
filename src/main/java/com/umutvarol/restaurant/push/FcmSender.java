package com.umutvarol.restaurant.push;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Notifications to the store apps (Android, and iOS through Firebase's APNs link) with Firebase Cloud Messaging,
 * HTTP v1. The server signs in as the Firebase project's service account (a JWT signed with its RSA key, exchanged
 * for an access token that is kept until shortly before it runs out). Off unless {@code FCM_SERVICE_ACCOUNT} holds
 * the service account's JSON, or the path of its file.
 */
@Component
class FcmSender {

    /** One HTTP exchange: the status and the body that came back. */
    interface Http {
        record Answer(int status, String body) {
        }

        Answer post(URI url, Map<String, String> headers, byte[] body);
    }

    private static final Logger log = LoggerFactory.getLogger(FcmSender.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final Http http;
    private final Clock clock;
    private final @Nullable Account account;
    private volatile @Nullable String accessToken;
    private volatile Instant accessUntil = Instant.EPOCH;

    record Account(String projectId, String clientEmail, PrivateKey key, URI tokenUri) {
    }

    FcmSender(@Value("${restaurant.push.fcm-service-account:}") String serviceAccount, Clock clock,
              org.springframework.beans.factory.ObjectProvider<Http> http) {
        this.clock = clock;
        this.http = http.getIfAvailable(() -> FcmSender::httpPost);
        this.account = read(serviceAccount);
        if (account != null) {
            log.info("Push: store apps through Firebase project {}", account.projectId());
        }
    }

    boolean enabled() {
        return account != null;
    }

    /** Sends one message; the HTTP status, and 404 for a token Firebase no longer knows. */
    int send(String token, String title, String body, String url, String tag) {
        if (account == null) {
            return 0;
        }
        try {
            Map<String, Object> message = Map.of("message", Map.of(
                    "token", token,
                    "notification", Map.of("title", title, "body", body),
                    "data", Map.of("url", url, "tag", tag),
                    "android", Map.of("priority", "high", "notification", Map.of("tag", tag)),
                    "apns", Map.of("headers", Map.of("apns-priority", "10", "apns-collapse-id", tag),
                            "payload", Map.of("aps", Map.of("sound", "default")))));
            Http.Answer answer = http.post(URI.create("https://fcm.googleapis.com/v1/projects/%s/messages:send".formatted(account.projectId())),
                    Map.of("Authorization", "Bearer " + accessToken(), "Content-Type", "application/json; charset=UTF-8"),
                    JSON.writeValueAsBytes(message));
            // a token of an app that was removed: Firebase says UNREGISTERED (404) or INVALID_ARGUMENT for the token
            if (answer.status() == 400 && answer.body().contains("registration token")) {
                return 404;
            }
            return answer.status();
        } catch (RuntimeException | GeneralSecurityException ex) {
            log.warn("Push: Firebase message failed: {}", ex.getMessage());
            return 0;
        }
    }

    private String accessToken() throws GeneralSecurityException {
        Instant now = clock.instant();
        String current = accessToken;
        if (current != null && now.isBefore(accessUntil)) {
            return current;
        }
        synchronized (this) {
            if (accessToken != null && now.isBefore(accessUntil)) {
                return accessToken;
            }
            String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                    + "&assertion=" + URLEncoder.encode(assertion(now), StandardCharsets.UTF_8);
            Http.Answer answer = http.post(account.tokenUri(), Map.of("Content-Type", "application/x-www-form-urlencoded"),
                    form.getBytes(StandardCharsets.UTF_8));
            if (answer.status() != 200) {
                throw new IllegalStateException("Firebase sign-in refused (" + answer.status() + ")");
            }
            JsonNode token = JSON.readTree(answer.body());
            accessToken = token.path("access_token").asString();
            accessUntil = now.plusSeconds(Math.max(60, token.path("expires_in").asLong(3600) - 300));
            return accessToken;
        }
    }

    /** The signed request for an access token (RFC 7523): RS256, for the messaging scope, an hour long. */
    String assertion(Instant now) throws GeneralSecurityException {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", account.clientEmail());
        claims.put("scope", SCOPE);
        claims.put("aud", account.tokenUri().toString());
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plus(Duration.ofHours(1)).getEpochSecond());
        String header = B64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = B64.encodeToString(JSON.writeValueAsBytes(claims));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(account.key());
        signer.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
        return header + "." + payload + "." + B64.encodeToString(signer.sign());
    }

    private static @Nullable Account read(String serviceAccount) {
        if (serviceAccount == null || serviceAccount.isBlank()) {
            return null;
        }
        try {
            String text = serviceAccount.strip().startsWith("{") ? serviceAccount : Files.readString(Path.of(serviceAccount.strip()));
            JsonNode json = JSON.readTree(text);
            String pem = json.path("private_key").asString().replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
            String tokenUri = json.path("token_uri").asString("https://oauth2.googleapis.com/token");
            if (!tokenUri.startsWith("https://")) {
                throw new IllegalArgumentException("token_uri must be https");
            }
            return new Account(json.path("project_id").asString(), json.path("client_email").asString(), key, URI.create(tokenUri));
        } catch (IOException | GeneralSecurityException | RuntimeException ex) {
            throw new IllegalStateException("FCM_SERVICE_ACCOUNT cannot be read as a Firebase service account", ex);
        }
    }

    private static Http.Answer httpPost(URI url, Map<String, String> headers, byte[] body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(request::header);
        try {
            HttpResponse<String> response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Http.Answer(response.statusCode(), response.body());
        } catch (IOException ex) {
            return new Http.Answer(0, ex.getMessage() == null ? "" : ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Http.Answer(0, "");
        }
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
}
