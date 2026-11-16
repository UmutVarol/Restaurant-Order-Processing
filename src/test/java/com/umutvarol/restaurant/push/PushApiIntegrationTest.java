package com.umutvarol.restaurant.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * A kitchen phone asks to hear of new orders; an order comes in; the push service gets a message only that phone can
 * read, signed by this server. A browser that unsubscribed (410) is forgotten. Anything but a browser push service is
 * refused as an address.
 */
@Import(PushApiIntegrationTest.Recorder.class)
class PushApiIntegrationTest extends ApiIntegrationTest {

    record Sent(URI endpoint, Map<String, String> headers, byte[] body) {
    }

    static final List<Sent> SENT = new CopyOnWriteArrayList<>();
    static volatile int answer = 201;

    @TestConfiguration
    static class Recorder {
        @Bean
        @Primary
        PushTransport recordingTransport() {
            return (endpoint, headers, body) -> {
                SENT.add(new Sent(endpoint, headers, body));
                return answer;
            };
        }
    }

    private static final Base64.Decoder B64 = Base64.getUrlDecoder();
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();

    @Test
    void aKitchenPhoneHearsOfANewOrderInAMessageOnlyItCanRead() throws Exception {
        String key = JsonPath.read(mvc.perform(as(CHEF, get("/api/v1/push/key"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.publicKey");
        assertThat(B64.decode(key)).hasSize(65);

        // only a browser's push service is an address
        for (String bad : List.of("https://evil.example.com/push", "http://fcm.googleapis.com/fcm/send/x", "https://fcm.googleapis.com:8443/x")) {
            mvc.perform(json(as(CHEF, post("/api/v1/push/subscriptions")), body(bad, ENC.encodeToString(new byte[65]), "NEW_ORDER")))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("PUSH_SUBSCRIPTION_INVALID"));
        }

        KeyPair phone = WebPushCrypto.newKeyPair();
        byte[] auth = new byte[16];
        new SecureRandom().nextBytes(auth);
        String endpoint = "https://fcm.googleapis.com/fcm/send/test-" + UUID.randomUUID();
        mvc.perform(json(as(CHEF, post("/api/v1/push/subscriptions")), """
                        {"endpoint": "%s", "keys": {"p256dh": "%s", "auth": "%s"}, "topics": ["NEW_ORDER"], "lang": "en"}"""
                        .formatted(endpoint, ENC.encodeToString(WebPushCrypto.raw((ECPublicKey) phone.getPublic())), ENC.encodeToString(auth))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.topics[0]").value("NEW_ORDER"));

        SENT.clear();
        long order = placeTakeaway("Push Guest", "Ayran", 1);
        Sent sent = waitFor(endpoint);
        // only the phone can read it
        String message = new String(decrypt(sent.body(), phone, auth), StandardCharsets.UTF_8);
        assertThat(message).contains("\"title\":\"New order #" + order + "\"").contains("Takeaway · Push Guest");
        assertThat(sent.headers()).containsEntry("Content-Encoding", "aes128gcm").containsKey("TTL");
        // signed by this server, for this push service
        String authorization = sent.headers().get("Authorization");
        assertThat(authorization).startsWith("vapid t=").endsWith(", k=" + key);
        String jwt = authorization.substring("vapid t=".length(), authorization.indexOf(','));
        String[] parts = jwt.split("\\.");
        assertThat(new String(B64.decode(parts[1]), StandardCharsets.UTF_8)).contains("\"aud\":\"https://fcm.googleapis.com\"");
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(WebPushCrypto.publicKey(B64.decode(key)));
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(B64.decode(parts[2]))).isTrue();

        // the browser has unsubscribed: the push service says 410, and the device is forgotten
        answer = 410;
        try {
            mvc.perform(as(CHEF, post("/api/v1/push/test"))).andExpect(jsonPath("$.devices").value(1));
            long until = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < until && !mine().isEmpty()) {
                Thread.sleep(50);
            }
            assertThat(mine()).isEmpty();
        } finally {
            answer = 201;
        }
    }

    private List<Object> mine() throws Exception {
        return JsonPath.read(mvc.perform(as(CHEF, get("/api/v1/push/subscriptions"))).andReturn().getResponse().getContentAsString(), "$");
    }

    private static Sent waitFor(String endpoint) throws InterruptedException {
        long until = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < until) {
            for (Sent sent : SENT) {
                if (sent.endpoint().toString().equals(endpoint)) {
                    return sent;
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("nothing was sent to " + endpoint);
    }

    private static String body(String endpoint, String p256dh, String topic) {
        return """
                {"endpoint": "%s", "keys": {"p256dh": "%s", "auth": "%s"}, "topics": ["%s"], "lang": "en"}"""
                .formatted(endpoint, p256dh, ENC.encodeToString(new byte[16]), topic);
    }

    /** What the browser does (RFC 8291 from the receiving end). */
    private static byte[] decrypt(byte[] body, KeyPair phone, byte[] auth) throws Exception {
        ByteBuffer in = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        in.get(salt);
        in.getInt();
        byte[] senderKey = new byte[in.get()];
        in.get(senderKey);
        byte[] record = Arrays.copyOfRange(body, in.position(), body.length);
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(phone.getPrivate());
        agreement.doPhase(WebPushCrypto.publicKey(senderKey), true);
        byte[] phonePublic = WebPushCrypto.raw((ECPublicKey) phone.getPublic());
        byte[] info = ByteBuffer.allocate(14 + 65 + 65).put("WebPush: info\0".getBytes(StandardCharsets.US_ASCII))
                .put(phonePublic).put(senderKey).array();
        byte[] ikm = WebPushCrypto.hkdf(auth, agreement.generateSecret(), info, 32);
        byte[] cek = WebPushCrypto.hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = WebPushCrypto.hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] padded = aes.doFinal(record);
        assertThat(padded[padded.length - 1]).isEqualTo((byte) 2);
        assertThat(((ECPrivateKey) phone.getPrivate()).getS()).isNotNull();
        return Arrays.copyOf(padded, padded.length - 1);
    }
}
