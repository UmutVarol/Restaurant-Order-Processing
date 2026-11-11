package com.umutvarol.restaurant.reservation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** A booking ahead of time; the table can be left open and chosen when the party arrives. */
public record BookingRequest(
        @NotBlank @Size(max = 60) String guestName,
        @Nullable @Pattern(regexp = ReservationRules.PHONE) String phone,
        @Min(1) @Max(30) int partySize,
        @NotNull Instant startsAt,
        @Nullable @Min(15) @Max(360) Integer durationMinutes,
        @Nullable Long tableId,
        @Nullable @Size(max = 140) String note) {
}
