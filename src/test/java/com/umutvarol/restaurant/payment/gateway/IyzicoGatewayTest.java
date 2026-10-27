package com.umutvarol.restaurant.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.umutvarol.restaurant.payment.gateway.OnlinePaymentGateway.CheckoutOutcome;
import com.umutvarol.restaurant.payment.gateway.OnlinePaymentGateway.CheckoutRequest;
import com.umutvarol.restaurant.payment.gateway.OnlinePaymentGateway.CheckoutSession;
import com.umutvarol.restaurant.payment.gateway.OnlinePaymentGateway.RefundOutcome;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Runs the iyzico client against a fake iyzico that checks our request signatures and signs its answers the way
 * iyzico does, so the signing code is tested in both directions without network access.
 */
class IyzicoGatewayTest {

    private static final String BASE = "https://sandbox-api.iyzipay.com";
    private static final IyzicoProperties PROPERTIES = new IyzicoProperties("sandbox-api-key", "sandbox-secret-key",
            URI.create(BASE), "guest@sofra.example", "11111111111", "Istanbul", "Turkey", "Moda Cad. No:1");

    private MockRestServiceServer iyzico;
    private IyzicoGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        iyzico = MockRestServiceServer.bindTo(builder).build();
        gateway = new IyzicoGateway(PROPERTIES, builder);
    }

    @Test
    void signsTheCheckoutRequestAndVerifiesTheAnswer() {
        AtomicReference<String> randomKey = new AtomicReference<>();
        iyzico.expect(requestTo(BASE + IyzicoGateway.INITIALIZE_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.conversationId").value("sofra-p7"))
                .andExpect(jsonPath("$.price").value("165.00"))
                .andExpect(jsonPath("$.paidPrice").value("165.00"))
                .andExpect(jsonPath("$.currency").value("TRY"))
                .andExpect(jsonPath("$.callbackUrl").value("https://sofra.example/api/v1/payments/callback/iyzico?link=abc"))
                .andExpect(jsonPath("$.buyer.name").value("Elif"))
                .andExpect(jsonPath("$.buyer.surname").value("Doğan"))
                .andExpect(jsonPath("$.basketItems[0].price").value("165.00"))
                .andExpect(request -> {
                    // IYZWSv2: base64("apiKey:…&randomKey:…&signature:hex(HMAC-SHA256(secret, rnd + path + body)))")
                    String rnd = request.getHeaders().getFirst("x-iyzi-rnd");
                    randomKey.set(rnd);
                    String body = ((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8);
                    String authorization = request.getHeaders().getFirst("Authorization");
                    assertThat(authorization).startsWith("IYZWSv2 ");
                    String decoded = new String(Base64.getDecoder().decode(authorization.substring(8)), StandardCharsets.UTF_8);
                    assertThat(decoded).isEqualTo("apiKey:sandbox-api-key&randomKey:" + rnd + "&signature:"
                            + gateway.hmacHex(rnd + IyzicoGateway.INITIALIZE_PATH + body));
                })
                .andRespond(withSuccess(signed("""
                        {"status":"success","conversationId":"sofra-p7","token":"tok-123",
                         "paymentPageUrl":"https://sandbox-cpp.iyzipay.com?token=tok-123"}""", "sofra-p7", "tok-123"),
                        MediaType.APPLICATION_JSON));

        CheckoutSession session = gateway.startCheckout(request());

        assertThat(session.token()).isEqualTo("tok-123");
        assertThat(session.paymentPageUrl()).isEqualTo("https://sandbox-cpp.iyzipay.com?token=tok-123");
        assertThat(randomKey.get()).isNotBlank();
        iyzico.verify();
    }

    @Test
    void rejectsAnAnswerWhoseSignatureDoesNotMatch() {
        iyzico.expect(requestTo(BASE + IyzicoGateway.INITIALIZE_PATH))
                .andRespond(withSuccess("""
                        {"status":"success","conversationId":"sofra-p7","token":"tok-123",
                         "paymentPageUrl":"https://evil.example","signature":"00ff"}""", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.startCheckout(request()))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void readsASuccessfulPaymentAndBindsItToTheCheckoutWeAskedAbout() {
        iyzico.expect(requestTo(BASE + IyzicoGateway.RETRIEVE_PATH))
                .andExpect(jsonPath("$.token").value("tok-123"))
                .andRespond(withSuccess(retrieveAnswer("SUCCESS", "sofra-p7", "tok-123"), MediaType.APPLICATION_JSON));

        CheckoutOutcome outcome = gateway.retrieve("tok-123", "sofra-p7");

        assertThat(outcome.status()).isEqualTo(CheckoutOutcome.Status.SUCCEEDED);
        assertThat(outcome.providerPaymentId()).isEqualTo("2900001");
        assertThat(outcome.paidAmount()).isEqualByComparingTo("165.00");
    }

    @Test
    void refusesAValidAnswerForAnotherPayment() {
        // Correctly signed, but for a different conversation: a replayed token from another checkout.
        iyzico.expect(requestTo(BASE + IyzicoGateway.RETRIEVE_PATH))
                .andRespond(withSuccess(retrieveAnswer("SUCCESS", "sofra-p99", "tok-123"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.retrieve("tok-123", "sofra-p7"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("conversationId");
    }

    @Test
    void aPaymentHeldForFraudReviewIsNeitherConfirmedNorGivenUp() {
        iyzico.expect(requestTo(BASE + IyzicoGateway.RETRIEVE_PATH))
                .andRespond(withSuccess(retrieveAnswer("SUCCESS", "sofra-p7", "tok-123", "TRY", 0), MediaType.APPLICATION_JSON));
        assertThat(gateway.retrieve("tok-123", "sofra-p7").status()).isEqualTo(CheckoutOutcome.Status.REVIEW);

        iyzico.reset();
        iyzico.expect(requestTo(BASE + IyzicoGateway.RETRIEVE_PATH))
                .andRespond(withSuccess(retrieveAnswer("SUCCESS", "sofra-p7", "tok-123", "TRY", -1), MediaType.APPLICATION_JSON));
        assertThat(gateway.retrieve("tok-123", "sofra-p7").status()).isEqualTo(CheckoutOutcome.Status.FAILED);
    }

    @Test
    void refusesAPaymentInAnotherCurrency() {
        iyzico.expect(requestTo(BASE + IyzicoGateway.RETRIEVE_PATH))
                .andRespond(withSuccess(retrieveAnswer("SUCCESS", "sofra-p7", "tok-123", "USD", 1), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.retrieve("tok-123", "sofra-p7"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void neverPrintsTheKeys() {
        assertThat(PROPERTIES.toString()).doesNotContain("sandbox-api-key").doesNotContain("sandbox-secret-key").contains("****");
    }

    @Test
    void reportsADeclinedCard() {
        iyzico.expect(requestTo(BASE + IyzicoGateway.RETRIEVE_PATH))
                .andRespond(withSuccess(retrieveAnswer("FAILURE", "sofra-p7", "tok-123"), MediaType.APPLICATION_JSON));

        assertThat(gateway.retrieve("tok-123", "sofra-p7").status()).isEqualTo(CheckoutOutcome.Status.FAILED);
    }

    @Test
    void tellsARefusedRefundFromAnUnreachableProvider() {
        iyzico.expect(requestTo(BASE + IyzicoGateway.REFUND_PATH))
                .andExpect(jsonPath("$.price").value("50.00"))
                .andRespond(withSuccess("""
                        {"status":"failure","errorCode":"5093","errorMessage":"Refund amount exceeds"}""",
                        MediaType.APPLICATION_JSON));
        RefundOutcome refused = gateway.refund("2900001", new BigDecimal("50"), "sofra-p7");
        assertThat(refused.succeeded()).isFalse();
        assertThat(refused.failureReason()).contains("5093");

        iyzico.reset();
        iyzico.expect(requestTo(BASE + IyzicoGateway.REFUND_PATH)).andRespond(withServerError());
        assertThatThrownBy(() -> gateway.refund("2900001", new BigDecimal("50"), "sofra-p7"))
                .isInstanceOf(PaymentGatewayException.class)
                .isNotInstanceOf(PaymentGatewayException.Refused.class);
    }

    @Test
    void refusesToStartWithoutKeys() {
        IyzicoProperties missing = new IyzicoProperties("", null, URI.create(BASE), "a@b.c", "1", "c", "c", "a");
        assertThatThrownBy(() -> new IyzicoGateway(missing, RestClient.builder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IYZICO_API_KEY");
    }

    private static CheckoutRequest request() {
        return new CheckoutRequest("sofra-p7", "B5001", new BigDecimal("165"), "Sofra Bistro - A1",
                "https://sofra.example/api/v1/payments/callback/iyzico?link=abc", "Elif Doğan", null, "203.0.113.9");
    }

    private String retrieveAnswer(String paymentStatus, String conversationId, String token) {
        return retrieveAnswer(paymentStatus, conversationId, token, "TRY", 1);
    }

    private String retrieveAnswer(String paymentStatus, String conversationId, String token, String currency, int fraudStatus) {
        String json = """
                {"status":"success","paymentStatus":"%s","paymentId":"2900001","currency":"%s","basketId":"B5001",
                 "conversationId":"%s","paidPrice":165.0,"price":165.0,"token":"%s","fraudStatus":%d}""".formatted(
                paymentStatus, currency, conversationId, token, fraudStatus);
        String signature = gateway.hmacHex(String.join(":", paymentStatus, "2900001", currency, "B5001", conversationId,
                "165", "165", token));
        return json.substring(0, json.length() - 1) + ",\"signature\":\"" + signature + "\"}";
    }

    private String signed(String json, String... fields) {
        String signature = gateway.hmacHex(String.join(":", fields));
        String trimmed = json.strip();
        return trimmed.substring(0, trimmed.length() - 1) + ",\"signature\":\"" + signature + "\"}";
    }
}
