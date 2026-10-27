package com.umutvarol.restaurant.payment.gateway;

import com.umutvarol.restaurant.payment.gateway.OnlinePaymentGateway.CheckoutOutcome.Status;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * iyzico's hosted Checkout Form, with 3-D Secure, called over its REST API.
 * <p>
 * Request signing ({@code IYZWSv2}) and response signature checks follow iyzico's official Java SDK
 * ({@code IyziAuthV2Generator}, {@code ResponseSignatureGenerator}). The SDK itself is not used because it
 * pulls in legacy {@code javax.*} Java EE APIs. Every response that carries a signature is verified, and
 * signatures are compared in constant time.
 */
public class IyzicoGateway implements OnlinePaymentGateway {

    public static final String PROVIDER = "IYZICO";
    static final String INITIALIZE_PATH = "/payment/iyzipos/checkoutform/initialize/auth/ecom";
    static final String RETRIEVE_PATH = "/payment/iyzipos/checkoutform/auth/ecom/detail";
    static final String REFUND_PATH = "/v2/payment/refund";

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private final IyzicoProperties properties;
    private final RestClient http;

    public IyzicoGateway(IyzicoProperties properties, RestClient.Builder http) {
        if (isBlank(properties.apiKey()) || isBlank(properties.secretKey())) {
            throw new IllegalStateException("iyzico is selected but IYZICO_API_KEY and IYZICO_SECRET_KEY are not set.");
        }
        this.properties = properties;
        this.http = http.baseUrl(properties.baseUrl().toString()).build();
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public CheckoutSession startCheckout(CheckoutRequest request) {
        String price = plain(request.amount());
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("contactName", request.buyerName());
        address.put("city", properties.city());
        address.put("country", properties.country());
        address.put("address", properties.address());

        Map<String, Object> buyer = new LinkedHashMap<>();
        String[] names = splitName(request.buyerName());
        buyer.put("id", "guest-" + request.basketId());
        buyer.put("name", names[0]);
        buyer.put("surname", names[1]);
        buyer.put("identityNumber", properties.buyerIdentityNumber());
        buyer.put("email", properties.buyerEmail());
        if (!isBlank(request.buyerPhone())) {
            buyer.put("gsmNumber", request.buyerPhone());
        }
        buyer.put("registrationAddress", properties.address());
        buyer.put("city", properties.city());
        buyer.put("country", properties.country());
        buyer.put("ip", request.buyerIp());

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", request.basketId());
        item.put("name", request.description());
        item.put("category1", "Restaurant");
        item.put("itemType", "PHYSICAL");
        item.put("price", price);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("locale", "tr");
        body.put("conversationId", request.conversationId());
        body.put("price", price);
        body.put("paidPrice", price);
        body.put("currency", "TRY");
        body.put("basketId", request.basketId());
        body.put("paymentGroup", "PRODUCT");
        body.put("callbackUrl", request.callbackUrl());
        body.put("enabledInstallments", List.of(1));
        body.put("buyer", buyer);
        body.put("shippingAddress", address);
        body.put("billingAddress", address);
        body.put("basketItems", List.of(item));

        JsonNode response = post(INITIALIZE_PATH, body);
        String token = text(response, "token");
        verify(response, List.of(text(response, "conversationId"), token));
        requireEqual("conversationId", request.conversationId(), text(response, "conversationId"));
        String pageUrl = text(response, "paymentPageUrl");
        if (isBlank(token) || isBlank(pageUrl)) {
            throw new PaymentGatewayException("iyzico did not return a payment page");
        }
        return new CheckoutSession(token, pageUrl);
    }

    @Override
    public CheckoutOutcome retrieve(String token, String conversationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("locale", "tr");
        body.put("conversationId", conversationId);
        body.put("token", token);

        JsonNode response = post(RETRIEVE_PATH, body);
        verify(response, List.of(text(response, "paymentStatus"), text(response, "paymentId"),
                text(response, "currency"), text(response, "basketId"), text(response, "conversationId"),
                decimal(response, "paidPrice"), decimal(response, "price"), text(response, "token")));
        // Bind the answer to the payment we asked about, so a token from another checkout cannot be replayed.
        requireEqual("conversationId", conversationId, text(response, "conversationId"));
        requireEqual("token", token, text(response, "token"));

        if ("SUCCESS".equals(text(response, "paymentStatus"))) {
            if (!"TRY".equals(text(response, "currency"))) {
                throw new PaymentGatewayException("iyzico answered with an unexpected currency");
            }
            // fraudStatus: 1 approved, 0 still being reviewed by iyzico's fraud team, -1 rejected.
            Integer fraud = integer(response, "fraudStatus");
            if (fraud != null && fraud == 0) {
                return new CheckoutOutcome(Status.REVIEW, text(response, "paymentId"), decimal(response, "paidPrice"), null);
            }
            if (fraud != null && fraud < 0) {
                return new CheckoutOutcome(Status.FAILED, text(response, "paymentId"), null,
                        "The payment was stopped by the provider's fraud checks.");
            }
            return new CheckoutOutcome(Status.SUCCEEDED, text(response, "paymentId"), decimal(response, "paidPrice"), null);
        }
        String reason = firstNonBlank(text(response, "errorMessage"), "The payment was not completed.");
        return new CheckoutOutcome(Status.FAILED, text(response, "paymentId"), null, reason);
    }

    @Override
    public RefundOutcome refund(String providerPaymentId, BigDecimal amount, String conversationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("locale", "tr");
        body.put("conversationId", conversationId);
        body.put("paymentId", providerPaymentId);
        body.put("price", plain(amount));
        body.put("ip", "127.0.0.1");
        try {
            JsonNode response = post(REFUND_PATH, body);
            verify(response, List.of(text(response, "paymentId"), decimal(response, "price"),
                    text(response, "currency"), text(response, "conversationId")));
            return new RefundOutcome(true, text(response, "paymentId"), null);
        } catch (PaymentGatewayException.Refused ex) {
            return new RefundOutcome(false, null, ex.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------- transport

    private JsonNode post(String path, Map<String, Object> body) {
        String json = JSON.writeValueAsString(body);
        String randomKey = UUID.randomUUID().toString();
        String response;
        try {
            response = http.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", authorization(path, json, randomKey))
                    .header("x-iyzi-rnd", randomKey)
                    .body(json)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException ex) {
            throw new PaymentGatewayException("iyzico could not be reached", ex);
        }
        JsonNode node = JSON.readTree(response == null ? "{}" : response);
        if (!"success".equals(text(node, "status"))) {
            throw new PaymentGatewayException.Refused("iyzico refused the request: %s %s".formatted(
                    firstNonBlank(text(node, "errorCode"), "?"), firstNonBlank(text(node, "errorMessage"), "")).trim());
        }
        return node;
    }

    /** {@code IYZWSv2 base64("apiKey:…&randomKey:…&signature:hex(HMAC-SHA256(secret, randomKey + path + body)))"}. */
    String authorization(String path, String body, String randomKey) {
        String signature = hmacHex(randomKey + path + body);
        String content = "apiKey:" + properties.apiKey() + "&randomKey:" + randomKey + "&signature:" + signature;
        return "IYZWSv2 " + Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
    }

    /** Response signatures: hex HMAC-SHA256 over the listed fields joined by {@code :}. */
    private void verify(JsonNode response, List<Object> fields) {
        String expected = hmacHex(fields.stream().map(IyzicoGateway::signatureValue).collect(Collectors.joining(":")));
        String actual = text(response, "signature");
        if (actual == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))) {
            throw new PaymentGatewayException("iyzico response signature did not verify");
        }
    }

    String hmacHex(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.secretKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("HMAC-SHA256 is not available", ex);
        }
    }

    static String signatureValue(@Nullable Object value) {
        if (value == null) {
            return "";
        }
        return value instanceof BigDecimal decimal ? decimal.stripTrailingZeros().toPlainString() : value.toString();
    }

    private static String plain(BigDecimal amount) {
        return amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static void requireEqual(String field, String expected, @Nullable String actual) {
        if (!expected.equals(actual)) {
            throw new PaymentGatewayException("iyzico answered for a different " + field);
        }
    }

    private static @Nullable String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static @Nullable Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static @Nullable BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isNumber() ? value.decimalValue() : new BigDecimal(value.asString());
    }

    private static String[] splitName(String name) {
        String trimmed = name == null ? "" : name.trim();
        int space = trimmed.lastIndexOf(' ');
        return space > 0
                ? new String[] {trimmed.substring(0, space), trimmed.substring(space + 1)}
                : new String[] {trimmed.isEmpty() ? "Guest" : trimmed, "-"};
    }

    private static String firstNonBlank(@Nullable String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
