package com.umutvarol.restaurant.guest.dto;

import com.umutvarol.restaurant.guest.GuestRequestKind;
import jakarta.validation.constraints.NotNull;

/** {@code CALL_WAITER} or {@code REQUEST_BILL}. */
public record GuestCallRequest(@NotNull GuestRequestKind kind) {
}
