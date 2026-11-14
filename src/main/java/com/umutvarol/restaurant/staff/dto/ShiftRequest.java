package com.umutvarol.restaurant.staff.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** A shift on the rota; the note says where or what (bar, opening, kitchen). */
public record ShiftRequest(@NotNull Long userId, @NotNull Instant startsAt, @NotNull Instant endsAt,
                           @Nullable @Size(max = 80) String note) {
}
