package com.umutvarol.restaurant.security.dto;

import com.umutvarol.restaurant.security.Role;
import java.time.Instant;

public record StaffResponse(Long id, String username, String displayName, Role role, boolean enabled, boolean hasPin,
                            boolean totpEnabled, boolean mustChangePassword, Instant createdAt, Long branchId) {
}
