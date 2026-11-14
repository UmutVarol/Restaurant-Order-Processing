package com.umutvarol.restaurant.staff.dto;

import com.umutvarol.restaurant.security.Role;

/** Hours one person worked in a period: for payroll. */
public record HoursRow(Long userId, String username, String displayName, Role role, long minutes, int entries,
                       boolean clockedInNow) {
}
