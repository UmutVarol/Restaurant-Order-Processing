package com.umutvarol.restaurant.guest;

import com.umutvarol.restaurant.guest.dto.GuestRequestResponse;
import com.umutvarol.restaurant.guest.dto.RejectRequest;
import com.umutvarol.restaurant.guest.dto.TableQrResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Guest requests", description = "The staff inbox for carts and calls sent from tables")
@RestController
class GuestRequestController {

    private final GuestService guests;

    GuestRequestController(GuestService guests) {
        this.guests = guests;
    }

    @Operation(summary = "Requests waiting for staff", description = "Oldest first.")
    @GetMapping("/api/v1/guest-requests")
    List<GuestRequestResponse> open() {
        return guests.open();
    }

    @Operation(summary = "Accept a cart", description = "Places it as a dine-in order, priced as of now.")
    @PostMapping("/api/v1/guest-requests/{id}/accept")
    GuestRequestResponse accept(@PathVariable Long id, Authentication authentication) {
        return guests.accept(id, authentication.getName());
    }

    @Operation(summary = "Turn a cart down", description = "The guest sees the reason.")
    @PostMapping("/api/v1/guest-requests/{id}/reject")
    GuestRequestResponse reject(@PathVariable Long id, @Valid @RequestBody RejectRequest request,
                                Authentication authentication) {
        return guests.reject(id, request.reason(), authentication.getName());
    }

    @Operation(summary = "Mark a call as dealt with")
    @PostMapping("/api/v1/guest-requests/{id}/done")
    GuestRequestResponse done(@PathVariable Long id, Authentication authentication) {
        return guests.done(id, authentication.getName());
    }

    @Operation(summary = "Issue a new QR code for a table", description = """
            Returns the token once, for printing; the table's previous code stops working.""")
    @PostMapping("/api/v1/tables/{id}/qr")
    TableQrResponse issueQr(@PathVariable Long id, Authentication authentication) {
        return guests.issueQr(id, authentication.getName());
    }
}
