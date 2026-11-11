package com.umutvarol.restaurant.reservation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** A party without a booking, put on the waitlist with the wait the host told them. */
public record WalkInRequest(
        @NotBlank @Size(max = 60) String guestName,
        @Nullable @Pattern(regexp = ReservationRules.PHONE) String phone,
        @Min(1) @Max(30) int partySize,
        @Nullable @Min(0) @Max(240) Integer quotedMinutes,
        @Nullable @Size(max = 140) String note) {
}
