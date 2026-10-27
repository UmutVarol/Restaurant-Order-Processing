package com.umutvarol.restaurant.payment;

import com.umutvarol.restaurant.payment.dto.BillResponse;
import com.umutvarol.restaurant.payment.dto.BillSummaryResponse;
import com.umutvarol.restaurant.payment.dto.CreateLinkRequest;
import com.umutvarol.restaurant.payment.dto.PaymentLinkResponse;
import com.umutvarol.restaurant.payment.dto.TakePaymentRequest;
import com.umutvarol.restaurant.payment.dto.TakePaymentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Bills", description = "What a table or order owes, and taking payment for it. WAITER or ADMIN.")
@RestController
@RequestMapping("/api/v1/bills")
class BillController {

    private final PaymentService paymentService;
    private final OnlinePaymentService onlinePaymentService;
    private final PublicUrls publicUrls;

    BillController(PaymentService paymentService, OnlinePaymentService onlinePaymentService, PublicUrls publicUrls) {
        this.paymentService = paymentService;
        this.onlinePaymentService = onlinePaymentService;
        this.publicUrls = publicUrls;
    }

    @Operation(summary = "Open bills", description = "Every bill that is not closed yet, oldest first.")
    @GetMapping
    List<BillSummaryResponse> open() {
        return paymentService.openBills();
    }

    @Operation(summary = "Get a bill")
    @GetMapping("/{id}")
    BillResponse get(@PathVariable Long id) {
        return paymentService.get(id);
    }

    @Operation(summary = "The bill of an order", description = "Dine-in orders share the bill of their table.")
    @GetMapping("/by-order/{orderId}")
    BillResponse forOrder(@PathVariable Long orderId) {
        return paymentService.forOrder(orderId);
    }

    @Operation(summary = "Take a cash or card payment",
            description = "Send an Idempotency-Key header to make retries safe: the same key returns the same payment. "
                    + "When the bill is paid in full, served dine-in orders are completed.")
    @PostMapping("/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    TakePaymentResponse take(@PathVariable Long id, @Valid @RequestBody TakePaymentRequest request,
                             @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                             Authentication authentication) {
        return paymentService.take(id, request, idempotencyKey, authentication.getName());
    }

    @Operation(summary = "Create a QR payment link",
            description = "The guest pays from their own phone. The token is returned only once.")
    @PostMapping("/{id}/links")
    @ResponseStatus(HttpStatus.CREATED)
    PaymentLinkResponse createLink(@PathVariable Long id, @Valid @RequestBody(required = false) CreateLinkRequest request,
                                   Authentication authentication) {
        return onlinePaymentService.createLink(id, request == null ? null : request.amount(), publicUrls.baseUrl(),
                authentication.getName());
    }
}
