package com.umutvarol.restaurant.staff.dto;

import com.umutvarol.restaurant.security.Role;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A day's tips and how they are shared: a preview until a manager closes the day, then fixed. {@code stillClockedIn}
 * lists who has to clock out (or be corrected) before the day can be closed.
 */
public record TipPoolResponse(LocalDate date, BigDecimal tipsTotal, boolean closed, String closedBy, Instant closedAt,
                              List<Share> shares, List<String> stillClockedIn) {

    public record Share(Long userId, String displayName, Role role, int minutes, BigDecimal weight,
                        BigDecimal amount) {
    }
}
