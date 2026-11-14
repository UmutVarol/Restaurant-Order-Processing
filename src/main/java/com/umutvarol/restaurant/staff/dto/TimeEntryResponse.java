package com.umutvarol.restaurant.staff.dto;

import java.time.Instant;

/** {@code forgotten}: still open after a longer stretch than any shift, so probably a missed clock-out. */
public record TimeEntryResponse(Long id, Long userId, String username, String displayName, Instant clockedInAt,
                                Instant clockedOutAt, long minutes, boolean forgotten, String clockedInBy,
                                String clockedOutBy, String correctedBy, String correctionReason) {
}
