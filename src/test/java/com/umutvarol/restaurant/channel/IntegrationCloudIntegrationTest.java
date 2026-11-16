package com.umutvarol.restaurant.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.business.BusinessRegistry;
import com.umutvarol.restaurant.integration.OutboundUrls;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Sofra Cloud: each business connects its own delivery platforms and e-Arşiv integrator. A platform's order reaches
 * a business only at its own address and only signed with its own secret; addresses on a private network are refused.
 */
@SpringBootTest(properties = {"restaurant.cloud.enabled=true", "restaurant.cloud.signup-enabled=true",
        "restaurant.cloud.base-domain=sofra.test", "restaurant.demo.enabled=false",
        "restaurant.security.rate-limit.public-requests-per-minute=100000",
        "restaurant.cloud.signups-per-client-per-hour=1000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IntegrationCloudIntegrationTest {

    private static final AtomicInteger NUMBERS = new AtomicInteger();
    private static final String KEBAB_SECRET = "kebab-webhook-secret-0123456789";
    private static final String CAFE_SECRET = "cafe-webhook-secret-9876543210";

    @Autowired
    MockMvc mvc;

    @Test
    void aPlatformOrderReachesABusinessOnlyAtItsOwnAddressWithItsOwnSecret() throws Exception {
        String[] kebab = open("Kebapçı");
        String[] cafe = open("Kafe");

        mvc.perform(with(kebab[1], get("/api/v1/integrations")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cloud").value(true))
                .andExpect(jsonPath("$.entries.length()").value(4))
                .andExpect(jsonPath("$.entries[?(@.kind == 'YEMEKSEPETI')].configured").value(false))
                .andExpect(jsonPath("$.entries[?(@.kind == 'YEMEKSEPETI')].webhookPath")
                        .value("/api/v1/channels/yemeksepeti/webhook/" + kebab[0]));

        String saved = mvc.perform(with(kebab[1], json(put("/api/v1/integrations/YEMEKSEPETI"), """
                        {"webhookSecret": "%s"}""".formatted(KEBAB_SECRET))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[?(@.kind == 'YEMEKSEPETI')].configured").value(true))
                .andExpect(jsonPath("$.entries[?(@.kind == 'YEMEKSEPETI')].keyHint").value("6789"))
                .andReturn().getResponse().getContentAsString();
        assertThat(saved).doesNotContain(KEBAB_SECRET);
        mvc.perform(with(cafe[1], json(put("/api/v1/integrations/YEMEKSEPETI"), """
                        {"webhookSecret": "%s"}""".formatted(CAFE_SECRET)))).andExpect(status().isOk());

        String order = """
                {"id": "YS-%d", "customer": {"name": "Deniz K.", "phone": "+90 5** *** 12 34", "address": "Moda"},
                 "items": [{"id": "YS-ITEM", "name": "Platform item", "quantity": 1, "unitPrice": 70.00, "options": []}],
                 "total": 70.00, "note": null}""".formatted(NUMBERS.incrementAndGet());
        // the kebab shop's own address, its own secret: taken
        webhook("/api/v1/channels/yemeksepeti/webhook/" + kebab[0], order, KEBAB_SECRET).andExpect(status().is2xxSuccessful());
        // the café's secret at the kebab shop's address: refused
        webhook("/api/v1/channels/yemeksepeti/webhook/" + kebab[0], order, CAFE_SECRET).andExpect(status().is4xxClientError());
        // an address naming no business: refused
        webhook("/api/v1/channels/yemeksepeti/webhook/no-such-place", order, KEBAB_SECRET).andExpect(status().is4xxClientError());
        // the kebab shop's order shows on its own platforms screen only
        mvc.perform(with(kebab[1], get("/api/v1/channels")))
                .andExpect(jsonPath("$[?(@.channel == 'YEMEKSEPETI')].mode").value("CONNECTED"));
        mvc.perform(with(kebab[1], delete("/api/v1/integrations/YEMEKSEPETI")))
                .andExpect(jsonPath("$.entries[?(@.kind == 'YEMEKSEPETI')].configured").value(false));
        webhook("/api/v1/channels/yemeksepeti/webhook/" + kebab[0], order, KEBAB_SECRET).andExpect(status().is4xxClientError());
    }

    @Test
    void anIntegratorOnAPrivateNetworkIsRefused() throws Exception {
        String[] kebab = open("Fatura");
        for (String address : new String[] {"http://einvoice.example.com", "https://127.0.0.1/api", "https://10.2.3.4",
                "https://169.254.169.254/latest", "https://[::1]/", "https://localhost/api", "https://user@example.com"}) {
            mvc.perform(with(kebab[1], json(put("/api/v1/integrations/E_ARSIV"), """
                            {"baseUrl": "%s", "apiKey": "integrator-key-12345678"}""".formatted(address))))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("INTEGRATION_URL_INVALID"));
        }
        mvc.perform(with(kebab[1], json(put("/api/v1/integrations/E_ARSIV"), """
                        {"baseUrl": "https://93.184.215.14/einvoice"}""")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INTEGRATION_INCOMPLETE"));
        // a public https address with its key is taken; the key never comes back
        String saved = mvc.perform(with(kebab[1], json(put("/api/v1/integrations/E_ARSIV"), """
                        {"baseUrl": "https://93.184.215.14/einvoice", "apiKey": "integrator-key-12345678"}""")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[?(@.kind == 'E_ARSIV')].configured").value(true))
                .andExpect(jsonPath("$.entries[?(@.kind == 'E_ARSIV')].baseUrl").value("https://93.184.215.14/einvoice"))
                .andExpect(jsonPath("$.entries[?(@.kind == 'E_ARSIV')].keyHint").value("5678"))
                .andReturn().getResponse().getContentAsString();
        assertThat(saved).doesNotContain("integrator-key-12345678");
    }

    @Test
    void publicAddressesAreTold() throws Exception {
        assertThat(OutboundUrls.isPublic(InetAddress.getByName("93.184.215.14"))).isTrue();
        assertThat(OutboundUrls.isPublic(InetAddress.getByName("192.168.1.10"))).isFalse();
        assertThat(OutboundUrls.isPublic(InetAddress.getByName("100.64.0.1"))).isFalse();
        assertThat(OutboundUrls.isPublic(InetAddress.getByName("fd00::1"))).isFalse();
        assertThat(OutboundUrls.isPublic(InetAddress.getByName("0.0.0.0"))).isFalse();
    }

    private ResultActions webhook(String path, String body, String secret) throws Exception {
        long now = Instant.now().getEpochSecond();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(bytes)
                .header(WebhookSignature.TIMESTAMP_HEADER, Long.toString(now))
                .header(WebhookSignature.SIGNATURE_HEADER, WebhookSignature.sign(secret, now, bytes)));
    }

    private String[] open(String name) throws Exception {
        String unique = name + " Bağlantı " + NUMBERS.incrementAndGet();
        String address = BusinessRegistry.suggestAddress(unique);
        String body = mvc.perform(json(post("/api/v1/public/signup"), """
                        {"businessName": "%s", "email": "owner@example.com", "ownerName": "Sahibi",
                         "username": "%s-owner", "password": "masa dört hazır 9"}""".formatted(unique, address)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new String[] {address, JsonPath.read(body, "$.session.accessToken")};
    }

    private static MockHttpServletRequestBuilder with(String token, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
