package com.umutvarol.restaurant.guest;

import com.umutvarol.restaurant.guest.dto.GuestCallRequest;
import com.umutvarol.restaurant.guest.dto.GuestOrderRequest;
import com.umutvarol.restaurant.guest.dto.GuestRequestView;
import com.umutvarol.restaurant.guest.dto.PublicTableResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Table QR", description = "What guests do from their table's QR code; the code is the permission")
@SecurityRequirements
@Validated
@RestController
@RequestMapping("/api/v1/public/table/{token}")
class PublicTableController {

    private final GuestService guests;

    PublicTableController(GuestService guests) {
        this.guests = guests;
    }

    @Operation(summary = "The table behind a QR code")
    @GetMapping
    PublicTableResponse table(@PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{20,100}") String token) {
        return guests.table(token);
    }

    @Operation(summary = "Send a cart", description = "A waiter accepts it before it reaches the kitchen.")
    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    GuestRequestView order(@PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{20,100}") String token,
                           @Valid @RequestBody GuestOrderRequest cart, HttpServletRequest http) {
        return guests.order(token, cart, http.getRemoteAddr());
    }

    @Operation(summary = "Call a waiter or ask for the bill")
    @PostMapping("/calls")
    @ResponseStatus(HttpStatus.CREATED)
    GuestRequestView call(@PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{20,100}") String token,
                          @Valid @RequestBody GuestCallRequest call, HttpServletRequest http) {
        return guests.call(token, call.kind(), http.getRemoteAddr());
    }

    @Operation(summary = "How a request is getting on")
    @GetMapping("/requests/{id}")
    GuestRequestView status(@PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{20,100}") String token,
                            @PathVariable @Pattern(regexp = "[0-9a-f-]{36}") String id) {
        return guests.status(token, id);
    }
}
