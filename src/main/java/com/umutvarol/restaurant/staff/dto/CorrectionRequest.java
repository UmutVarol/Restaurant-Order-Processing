package com.umutvarol.restaurant.staff.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** A manager's fix to a time entry; the reason is kept with it. Leaving the end empty keeps it open. */
public record CorrectionRequest(@NotNull Instant clockedInAt, @Nullable Instant clockedOutAt,
                                @NotBlank @Size(max = 140) String reason) {
}
