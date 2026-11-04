package com.umutvarol.restaurant.payment;

import com.umutvarol.restaurant.guest.GuestService;
import com.umutvarol.restaurant.payment.dto.PaymentLinkResponse;
import com.umutvarol.restaurant.payment.dto.TableBillResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Guests paying their table's bill from its QR code, without waiting for a waiter to bring a payment link. */
@Tag(name = "Table QR", description = "What guests do from their table's QR code; the code is the permission")
@SecurityRequirements
@Validated
@RestController
@RequestMapping("/api/v1/public/table/{token}/bill")
class PublicTableBillController {

    private final GuestService guests;
    private final OnlinePaymentService onlinePayments;
    private final PublicUrls publicUrls;

    PublicTableBillController(GuestService guests, OnlinePaymentService onlinePayments, PublicUrls publicUrls) {
        this.guests = guests;
        this.onlinePayments = onlinePayments;
        this.publicUrls = publicUrls;
    }

    @Operation(summary = "What the table owes, and whether it can be paid from a phone")
    @GetMapping
    TableBillResponse bill(@PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{20,100}") String token) {
        return onlinePayments.tableBill(guests.tableFor(token));
    }

    @Operation(summary = "Open the bill to pay it", description = "A payment link for whatever is due on the table's bill.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    PaymentLinkResponse open(@PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{20,100}") String token) {
        return onlinePayments.openForTable(guests.tableFor(token), publicUrls.baseUrl());
    }
}
