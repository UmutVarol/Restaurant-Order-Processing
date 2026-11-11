package com.umutvarol.restaurant.reservation.dto;

import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;

/**
 * Seat a party at a table. {@code overrideBooking} seats them even though the table is booked by someone else soon;
 * the host has to choose it knowingly.
 */
public record SeatRequest(@NotNull Long tableId, @Nullable Boolean overrideBooking) {

    public boolean overrides() {
        return Boolean.TRUE.equals(overrideBooking);
    }
}
