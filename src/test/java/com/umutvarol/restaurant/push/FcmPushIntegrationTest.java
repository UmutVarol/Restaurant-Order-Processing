package com.umutvarol.restaurant.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The store app on a waiter's phone hears that an order is ready, through Firebase: the server signs in as the
 * project's service account, then sends the message to the phone's token. A token Firebase no longer knows is dropped.
 */
@Import(FcmPushIntegrationTest.FakeFirebase.class)
class FcmPushIntegrationTest extends ApiIntegrationTest {

    record Call(URI url, Map<String, String> headers, String body) {
    }

    static final List<Call> CALLS = new CopyOnWriteArrayList<>();
    static volatile int sendStatus = 200;
    static final KeyPair ACCOUNT_KEY = rsa();

    @TestConfiguration
    static class FakeFirebase {
        @Bean
        FcmSender.Http fakeFirebase() {
            return (url, headers, body) -> {
                CALLS.add(new Call(url, headers, new String(body, StandardCharsets.UTF_8)));
                if (url.toString().endsWith("/token")) {
                    return new FcmSender.Http.Answer(200, "{\"access_token\":\"ya29.test-token\",\"expires_in\":3600}");
                }
                return new FcmSender.Http.Answer(sendStatus, sendStatus == 404 ? "{\"error\":{\"status\":\"NOT_FOUND\"}}" : "{\"name\":\"m/1\"}");
            };
        }
    }

    @DynamicPropertySource
    static void serviceAccount(DynamicPropertyRegistry registry) {
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(ACCOUNT_KEY.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        registry.add("restaurant.push.fcm-service-account", () -> """
                {"type": "service_account", "project_id": "sofra-test", "client_email": "push@sofra-test.iam.gserviceaccount.com",
                 "private_key": "%s", "token_uri": "https://oauth2.googleapis.com/token"}""".formatted(pem.replace("\n", "\\n")));
    }

    @Test
    void aWaitersPhoneHearsOfAReadyOrderThroughFirebase() throws Exception {
        mvc.perform(as(WAITER, get("/api/v1/push/key"))).andExpect(jsonPath("$.native").value(true));
        mvc.perform(json(as(WAITER, post("/api/v1/push/native")), """
                        {"token": "not a token", "topics": ["READY"], "lang": "tr"}"""))
                .andExpect(status().isUnprocessableContent());
        String token = "fcm-test-" + UUID.randomUUID() + ":APA91b-abcdefghij";
        mvc.perform(json(as(WAITER, post("/api/v1/push/native")), """
                        {"token": "%s", "topics": ["READY"], "lang": "tr"}""".formatted(token)))
                .andExpect(status().isCreated());

        CALLS.clear();
        long order = placeTakeaway("Firebase Guest", "Ayran", 1);
        for (int step = 0; step < 2; step++) {
            mvc.perform(as(CHEF, post("/api/v1/orders/{id}/advance", order))).andExpect(status().isOk());
        }
        Call send = waitFor("messages:send");
        assertThat(send.url().toString()).isEqualTo("https://fcm.googleapis.com/v1/projects/sofra-test/messages:send");
        assertThat(send.headers()).containsEntry("Authorization", "Bearer ya29.test-token");
        assertThat(send.body()).contains("\"token\":\"" + token + "\"").contains("Hazır: #" + order).contains("\"url\":\"#/service\"");

        // the sign-in: an RS256 assertion from the service account, for the messaging scope
        Call signIn = CALLS.stream().filter(call -> call.url().toString().endsWith("/token")).findFirst().orElseThrow();
        String assertion = URLDecoder.decode(signIn.body().replaceAll(".*assertion=", ""), StandardCharsets.UTF_8);
        String[] parts = assertion.split("\\.");
        assertThat(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8))
                .contains("\"iss\":\"push@sofra-test.iam.gserviceaccount.com\"").contains("firebase.messaging");
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(ACCOUNT_KEY.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();

        // the app was removed from the phone: Firebase no longer knows the token, so it is forgotten
        sendStatus = 404;
        try {
            mvc.perform(as(WAITER, post("/api/v1/push/test"))).andExpect(jsonPath("$.devices").value(1));
            long until = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < until && !mine().isEmpty()) {
                Thread.sleep(50);
            }
            assertThat(mine()).isEmpty();
        } finally {
            sendStatus = 200;
        }
    }

    private List<Object> mine() throws Exception {
        return JsonPath.read(mvc.perform(as(WAITER, get("/api/v1/push/subscriptions"))).andReturn().getResponse().getContentAsString(), "$");
    }

    private static Call waitFor(String part) throws InterruptedException {
        long until = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < until) {
            for (Call call : CALLS) {
                if (call.url().toString().contains(part)) {
                    return call;
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Firebase was not called");
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
