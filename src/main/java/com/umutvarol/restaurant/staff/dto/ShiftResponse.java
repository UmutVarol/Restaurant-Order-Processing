package com.umutvarol.restaurant.staff.dto;

import com.umutvarol.restaurant.security.Role;
import java.time.Instant;

public record ShiftResponse(Long id, Long userId, String username, String displayName, Role role, Instant startsAt,
                            Instant endsAt, String note) {
}
