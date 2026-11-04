package com.umutvarol.restaurant.fiscal;

import com.umutvarol.restaurant.fiscal.dto.GuestInvoiceResponse;
import com.umutvarol.restaurant.fiscal.dto.InvoiceRequest;
import com.umutvarol.restaurant.payment.OnlinePaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A guest paying from their phone gives their invoice details there, instead of telling them to the waiter. */
@Tag(name = "Guest payments", description = "Paying a bill from the guest's phone via a QR link. No sign-in.")
@SecurityRequirements
@Validated
@RestController
@RequestMapping("/api/v1/public/pay/{token}/invoice")
class PublicInvoiceController {

    private final OnlinePaymentService onlinePayments;
    private final FiscalService fiscal;

    PublicInvoiceController(OnlinePaymentService onlinePayments, FiscalService fiscal) {
        this.onlinePayments = onlinePayments;
        this.fiscal = fiscal;
    }

    @Operation(summary = "Whether an invoice can be asked for, and whose name it is in")
    @GetMapping
    GuestInvoiceResponse invoice(@PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{20,100}") String token) {
        return fiscal.guestInvoice(onlinePayments.billOfLink(token, false));
    }

    @Operation(summary = "Ask for an e-Arşiv invoice", description = """
            VKN (10 digits) or TCKN (11 digits), check digits verified. Issued when the bill is paid. Details given \\
            once cannot be changed from a phone.""")
    @PostMapping
    GuestInvoiceResponse request(@PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{20,100}") String token,
                                 @Valid @RequestBody InvoiceRequest request) {
        return fiscal.requestInvoiceAsGuest(onlinePayments.billOfLink(token, true), request);
    }
}
