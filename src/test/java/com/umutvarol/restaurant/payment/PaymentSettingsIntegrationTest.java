package com.umutvarol.restaurant.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.business.BusinessContext;
import com.umutvarol.restaurant.business.BusinessRegistry;
import com.umutvarol.restaurant.business.BusinessRepository;
import com.umutvarol.restaurant.payment.gateway.OnlinePaymentGateway;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Sofra Cloud: a business connects its own iyzico account. Its guests' payments go through that account, every other
 * business keeps the server's provider, the keys are stored sealed and never shown again.
 */
@SpringBootTest(properties = {"restaurant.cloud.enabled=true", "restaurant.cloud.signup-enabled=true",
        "restaurant.cloud.base-domain=sofra.test", "restaurant.demo.enabled=false",
        "restaurant.security.rate-limit.public-requests-per-minute=100000",
        "restaurant.cloud.signups-per-client-per-hour=1000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentSettingsIntegrationTest {

    private static final AtomicInteger NUMBERS = new AtomicInteger();
    private static final String API_KEY = "sandbox-AbCdEf0123456789";
    private static final String SECRET = "sandbox-SeCrEt9876543210";

    @Autowired
    MockMvc mvc;
    @Autowired
    OnlinePayments online;
    @Autowired
    BusinessRepository businesses;
    @Autowired
    PaymentSettingsRepository settings;

    @Test
    void aBusinessConnectsItsOwnAccountAndNoOtherBusinessUsesIt() throws Exception {
        String[] kebab = open("Kebapçı");
        String[] cafe = open("Kafe");
        long kebabId = id(kebab[0]);
        long cafeId = id(cafe[0]);

        mvc.perform(with(kebab[1], get("/api/v1/payment-settings")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.serverProvider").value("SIMULATED"))
                .andExpect(jsonPath("$.encryptionReady").value(true));
        mvc.perform(with(kebab[1], json(put("/api/v1/payment-settings"), """
                        {"apiKey": "short", "secretKey": "%s", "sandbox": true}""".formatted(SECRET))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PAYMENT_KEY_INVALID"));

        String body = mvc.perform(with(kebab[1], json(put("/api/v1/payment-settings"), """
                        {"apiKey": "%s", "secretKey": "%s", "sandbox": true}""".formatted(API_KEY, SECRET))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.provider").value("IYZICO"))
                .andExpect(jsonPath("$.keyHint").value("6789"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(API_KEY, SECRET);
        // stored sealed, never in clear
        PaymentSettings stored = BusinessContext.call(kebabId, () -> settings.findById(kebabId).orElseThrow());
        assertThat(stored.getApiKey()).doesNotContain("AbCdEf").isNotEqualTo(API_KEY);
        assertThat(stored.getSecretKey()).doesNotContain("SeCrEt");

        // the kebab shop's guests pay through its own account; the café keeps the server's provider
        assertThat(BusinessContext.call(kebabId, () -> online.gateway().map(OnlinePaymentGateway::provider).orElseThrow()))
                .isEqualTo("IYZICO");
        assertThat(BusinessContext.call(cafeId, () -> online.gateway().map(OnlinePaymentGateway::provider).orElseThrow()))
                .isEqualTo("SIMULATED");
        mvc.perform(with(cafe[1], get("/api/v1/payment-settings"))).andExpect(jsonPath("$.configured").value(false));

        // disconnecting goes back to the server's provider at once
        mvc.perform(with(kebab[1], delete("/api/v1/payment-settings"))).andExpect(jsonPath("$.configured").value(false));
        assertThat(BusinessContext.call(kebabId, () -> online.gateway().map(OnlinePaymentGateway::provider).orElseThrow()))
                .isEqualTo("SIMULATED");
    }

    @Test
    void aProviderNotificationIsHandledAsTheBusinessOfItsPayment() throws Exception {
        String[] cafe = open("Bildirim");
        String menu = mvc.perform(with(cafe[1], get("/api/v1/menu"))).andReturn().getResponse().getContentAsString();
        int dish = ((java.util.List<Integer>) JsonPath.read(menu, "$[?(@.name == 'Ayran')].id")).getFirst();
        String order = mvc.perform(with(cafe[1], json(post("/api/v1/orders"), """
                        {"type": "TAKEAWAY", "customerName": "Webhook", "paymentMethod": "CASH",
                         "items": [{"menuItemId": %d, "quantity": 1}]}""".formatted(dish))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long orderId = ((Number) JsonPath.read(order, "$.id")).longValue();
        String bill = mvc.perform(with(cafe[1], get("/api/v1/bills/by-order/{id}", orderId))).andReturn().getResponse().getContentAsString();
        long billId = ((Number) JsonPath.read(bill, "$.id")).longValue();
        String link = mvc.perform(with(cafe[1], post("/api/v1/bills/{id}/links", billId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String checkout = mvc.perform(json(post("/api/v1/public/pay/{token}/checkout", (String) JsonPath.read(link, "$.token")), "{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String providerToken = ((String) JsonPath.read(checkout, "$.paymentPageUrl")).substring("/#/sim-checkout/".length());
        mvc.perform(json(post("/api/v1/public/sim-checkout/{token}", providerToken), """
                        {"approve": true}"""));

        // the guest never came back: only the provider's notification, which names no business, says so
        mvc.perform(json(post("/api/v1/payments/webhook/simulated"), """
                        {"token": "%s", "status": "SUCCESS"}""".formatted(providerToken)))
                .andExpect(status().isOk());
        mvc.perform(with(cafe[1], get("/api/v1/bills/{id}", billId)))
                .andExpect(jsonPath("$.payments[0].status").value("SUCCEEDED"));
    }

    private long id(String address) {
        return BusinessContext.acrossAll(() -> businesses.findBySlug(address).orElseThrow().getId());
    }

    private String[] open(String name) throws Exception {
        String unique = name + " Ödeme " + NUMBERS.incrementAndGet();
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
