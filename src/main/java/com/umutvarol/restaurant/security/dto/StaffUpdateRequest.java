package com.umutvarol.restaurant.security.dto;

import com.umutvarol.restaurant.security.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Changing the role or switching an account off ends its sessions at once. */
public record StaffUpdateRequest(@NotBlank @Size(max = 80) String displayName, @NotNull Role role, boolean enabled,
                                 @org.jspecify.annotations.Nullable Long branchId) {
}
