package com.umutvarol.restaurant.reservation;

import com.umutvarol.restaurant.reservation.dto.BookingRequest;
import com.umutvarol.restaurant.reservation.dto.ReservationResponse;
import com.umutvarol.restaurant.reservation.dto.SeatRequest;
import com.umutvarol.restaurant.reservation.dto.TableOption;
import com.umutvarol.restaurant.reservation.dto.WalkInRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Reservations", description = "Bookings, the walk-in waitlist, and seating parties at tables")
@RestController
@Validated
@RequestMapping("/api/v1/reservations")
class ReservationController {

    private final ReservationService reservations;

    ReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    @Operation(summary = "A day's book", description = """
            The day's bookings in any status (today in the restaurant's time zone by default), plus every party on
            the waitlist or seated.""")
    @GetMapping
    List<ReservationResponse> day(@RequestParam(required = false) @Nullable LocalDate date) {
        return reservations.day(date);
    }

    @Operation(summary = "Take a booking", description = "The table may be left open and chosen when the party arrives.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ReservationResponse book(@Valid @RequestBody BookingRequest request, Authentication authentication) {
        return reservations.book(request, authentication.getName());
    }

    @Operation(summary = "Change a booking or a waiting party")
    @PutMapping("/{id}")
    ReservationResponse update(@PathVariable Long id, @Valid @RequestBody BookingRequest request,
                               Authentication authentication) {
        return reservations.update(id, request, authentication.getName());
    }

    @Operation(summary = "Put a walk-in on the waitlist")
    @PostMapping("/walk-ins")
    @ResponseStatus(HttpStatus.CREATED)
    ReservationResponse walkIn(@Valid @RequestBody WalkInRequest request, Authentication authentication) {
        return reservations.addWalkIn(request, authentication.getName());
    }

    @Operation(summary = "Tables free for a party", description = "Enough seats and nobody else for the whole visit.")
    @GetMapping("/free-tables")
    List<TableOption> freeTables(@RequestParam Instant startsAt, @RequestParam @Min(1) @Max(30) int partySize,
                                 @RequestParam(required = false) @Nullable @Min(15) @Max(360) Integer durationMinutes,
                                 @RequestParam(required = false) @Nullable Long except) {
        return reservations.freeTables(startsAt, partySize, durationMinutes, except);
    }

    @Operation(summary = "Seat a party", description = """
            Refused when the table is in use, too small, or booked by someone else soon (unless overrideBooking).""")
    @PostMapping("/{id}/seat")
    ReservationResponse seat(@PathVariable Long id, @Valid @RequestBody SeatRequest request,
                             Authentication authentication) {
        return reservations.seat(id, request, authentication.getName());
    }

    @Operation(summary = "Cancel a booking, or take a party off the waitlist")
    @PostMapping("/{id}/cancel")
    ReservationResponse cancel(@PathVariable Long id, Authentication authentication) {
        return reservations.cancel(id, authentication.getName());
    }

    @Operation(summary = "Mark a booking as a no-show", description = "Happens on its own some minutes after its time.")
    @PostMapping("/{id}/no-show")
    ReservationResponse noShow(@PathVariable Long id, Authentication authentication) {
        return reservations.noShow(id, authentication.getName());
    }

    @Operation(summary = "The party left", description = "Happens on its own when the last order at their table is paid.")
    @PostMapping("/{id}/finish")
    ReservationResponse finish(@PathVariable Long id, Authentication authentication) {
        return reservations.finish(id, authentication.getName());
    }
}
