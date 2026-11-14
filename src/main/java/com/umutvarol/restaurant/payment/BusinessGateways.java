package com.umutvarol.restaurant.payment;

import com.umutvarol.restaurant.business.BusinessContext;
import com.umutvarol.restaurant.payment.gateway.IyzicoGateway;
import com.umutvarol.restaurant.payment.gateway.IyzicoProperties;
import com.umutvarol.restaurant.payment.gateway.OnlinePaymentGateway;
import com.umutvarol.restaurant.security.totp.SecretBox;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The payment provider of the business a request or job works for, when it set up its own account (Sofra Cloud).
 * Built once per business from its sealed keys and remembered until the keys change.
 */
@Component
class BusinessGateways {

    static final URI SANDBOX = URI.create("https://sandbox-api.iyzipay.com");
    static final URI LIVE = URI.create("https://api.iyzipay.com");

    private static final Logger log = LoggerFactory.getLogger(BusinessGateways.class);

    private final PaymentSettingsRepository settings;
    private final SecretBox box;
    private final IyzicoProperties defaults;
    private final Map<Long, Optional<OnlinePaymentGateway>> built = new ConcurrentHashMap<>();

    BusinessGateways(PaymentSettingsRepository settings, SecretBox box, IyzicoProperties defaults) {
        this.settings = settings;
        this.box = box;
        this.defaults = defaults;
    }

    /** The current business's own provider; empty when it has none (the server's, if any, is used then). */
    Optional<OnlinePaymentGateway> current() {
        Long business = BusinessContext.current();
        if (business == null) {
            return Optional.empty();
        }
        Optional<OnlinePaymentGateway> known = built.get(business);
        if (known != null) {
            return known;
        }
        // built outside the map's lock: a database call inside it would pin a virtual thread (docs/LOAD_TEST.md)
        Optional<OnlinePaymentGateway> gateway = settings.findById(business).flatMap(this::build);
        built.put(business, gateway);
        return gateway;
    }

    void forget(long business) {
        built.remove(business);
    }

    private Optional<OnlinePaymentGateway> build(PaymentSettings own) {
        if (!box.available()) {
            log.error("Business {} has its own payment keys but the encryption key is not set: online payments are off for it",
                    own.getId());
            return Optional.empty();
        }
        String apiKey = new String(box.open(own.getApiKey()), StandardCharsets.UTF_8);
        String secretKey = new String(box.open(own.getSecretKey()), StandardCharsets.UTF_8);
        IyzicoProperties properties = new IyzicoProperties(apiKey, secretKey, own.isSandbox() ? SANDBOX : LIVE,
                defaults.buyerEmail(), defaults.buyerIdentityNumber(), defaults.city(), defaults.country(), defaults.address());
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        return Optional.of(new IyzicoGateway(properties, RestClient.builder().requestFactory(factory)));
    }
}
