package com.umutvarol.restaurant.guest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Shown to the guest, so it is kept short and polite. */
public record RejectRequest(@NotBlank @Size(max = 140) String reason) {
}
