package com.umutvarol.restaurant.payment;

import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.payment.dto.CheckoutResponse;
import com.umutvarol.restaurant.payment.dto.PublicBillResponse;
import com.umutvarol.restaurant.payment.dto.PublicCheckoutRequest;
import com.umutvarol.restaurant.payment.dto.SimulatedCheckoutResponse;
import com.umutvarol.restaurant.payment.dto.SimulatedDecisionRequest;
import com.umutvarol.restaurant.payment.gateway.OnlinePaymentGateway.CheckoutRequest;
import com.umutvarol.restaurant.payment.gateway.PaymentGatewayException;
import com.umutvarol.restaurant.payment.gateway.SimulatedGateway;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints a guest reaches without signing in. Access to a bill is granted by the unguessable link token alone,
 * and only what the guest needs to pay is shown.
 */
@Tag(name = "Guest payments", description = "Paying a bill from the guest's phone via a QR link. No sign-in.")
@RestController
class PublicPaymentController {

    private final OnlinePaymentService onlinePaymentService;
    private final OnlinePayments online;
    private final PublicUrls publicUrls;

    PublicPaymentController(OnlinePaymentService onlinePaymentService, OnlinePayments online, PublicUrls publicUrls) {
        this.onlinePaymentService = onlinePaymentService;
        this.online = online;
        this.publicUrls = publicUrls;
    }

    @Operation(summary = "What a payment link is for")
    @GetMapping("/api/v1/public/pay/{token}")
    PublicBillResponse view(@PathVariable String token) {
        return onlinePaymentService.view(token);
    }

    @Operation(summary = "Start paying", description = "Returns the provider's secure page to send the guest to.")
    @PostMapping("/api/v1/public/pay/{token}/checkout")
    CheckoutResponse checkout(@PathVariable String token, @Valid @RequestBody PublicCheckoutRequest request,
                              HttpServletRequest http) {
        return onlinePaymentService.checkout(token, request, publicUrls.baseUrl(), http.getRemoteAddr());
    }

    /**
     * Where the provider sends the guest back to, with the checkout token as a form field. The outcome is then
     * asked from the provider; nothing in this request is trusted. The guest lands back on the payment link.
     */
    @Operation(summary = "Provider callback", description = "Called by the guest's browser after the provider's page.")
    @PostMapping(value = "/api/v1/payments/callback/{provider}", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<Void> callback(@PathVariable String provider, @RequestParam(required = false) String token) {
        if (token != null && !token.isBlank() && token.length() <= 200) {
            onlinePaymentService.handleCallback(provider, token);
        }
        // The page remembers which link the guest came from; the link token never travels through the provider.
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(OnlinePaymentService.RETURN_PATH)).build();
    }

    /**
     * Server-to-server notification from the provider (iyzico webhook), for guests who paid but never came back.
     * Always answers 200 so the provider stops retrying, and so the answer reveals nothing about our payments.
     */
    @Operation(summary = "Provider notification", description = "Only triggers a check with the provider; the body is not trusted.")
    @PostMapping(value = "/api/v1/payments/webhook/{provider}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> notification(@PathVariable String provider, @RequestBody Map<String, Object> body) {
        onlinePaymentService.handleNotification(provider, body);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Simulated provider: the payment page", description = "Demo only.")
    @GetMapping("/api/v1/public/sim-checkout/{token}")
    SimulatedCheckoutResponse simulatedPage(@PathVariable String token) {
        CheckoutRequest request = session(token);
        return new SimulatedCheckoutResponse(token, request.amount(), request.description(), request.callbackUrl());
    }

    @Operation(summary = "Simulated provider: approve or decline", description = "Demo only.")
    @PostMapping("/api/v1/public/sim-checkout/{token}")
    SimulatedCheckoutResponse simulatedDecision(@PathVariable String token,
                                                @Valid @RequestBody SimulatedDecisionRequest decision) {
        CheckoutRequest request = session(token);
        simulated().decide(token, decision.approve());
        return new SimulatedCheckoutResponse(token, request.amount(), request.description(), request.callbackUrl());
    }

    private CheckoutRequest session(String token) {
        try {
            return simulated().session(token);
        } catch (PaymentGatewayException ex) {
            throw new ResourceNotFoundException("checkout", "?");
        }
    }

    private SimulatedGateway simulated() {
        return online.simulated().orElseThrow(() -> new ResourceNotFoundException("checkout", "?"));
    }
}
