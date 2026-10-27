package com.umutvarol.restaurant.payment.gateway;

import com.umutvarol.restaurant.payment.gateway.OnlinePaymentGateway.CheckoutOutcome.Status;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A stand-in provider for demos and tests. Its "payment page" is a screen of the web app where the guest picks
 * approve or decline; nothing is charged. It is refused at start-up outside demo mode unless explicitly allowed,
 * because anyone could "pay" with it.
 */
public class SimulatedGateway implements OnlinePaymentGateway {

    public static final String PROVIDER = "SIMULATED";

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public CheckoutSession startCheckout(CheckoutRequest request) {
        String token = "sim_" + UUID.randomUUID().toString().replace("-", "");
        sessions.put(token, new Session(request, Status.PENDING));
        return new CheckoutSession(token, "/#/sim-checkout/" + token);
    }

    /** What the simulated payment page shows: the amount and where to send the guest afterwards. */
    public CheckoutRequest session(String token) {
        Session session = sessions.get(token);
        if (session == null) {
            throw new PaymentGatewayException("Unknown simulated checkout " + token);
        }
        return session.request();
    }

    /** The guest pressed approve or decline on the simulated page. */
    public void decide(String token, boolean approve) {
        sessions.computeIfPresent(token, (key, session) -> session.status() == Status.PENDING
                ? new Session(session.request(), approve ? Status.SUCCEEDED : Status.FAILED)
                : session);
    }

    @Override
    public CheckoutOutcome retrieve(String token, String conversationId) {
        Session session = sessions.get(token);
        if (session == null || !session.request().conversationId().equals(conversationId)) {
            return new CheckoutOutcome(Status.FAILED, null, null, "Unknown checkout");
        }
        return switch (session.status()) {
            case SUCCEEDED -> new CheckoutOutcome(Status.SUCCEEDED, "simpay_" + token.substring(4, 16),
                    session.request().amount(), null);
            case FAILED -> new CheckoutOutcome(Status.FAILED, null, null, "The card was declined (simulated).");
            case PENDING, REVIEW -> CheckoutOutcome.pending();
        };
    }

    @Override
    public RefundOutcome refund(String providerPaymentId, BigDecimal amount, String conversationId) {
        return new RefundOutcome(true, "simref_" + UUID.randomUUID().toString().substring(0, 8), null);
    }

    private record Session(CheckoutRequest request, Status status) {
    }
}
