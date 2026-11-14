package com.umutvarol.restaurant.security.dto;

import com.umutvarol.restaurant.security.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * A new staff account. The username is what they type to sign in (lower case letters, digits, dot, dash,
 * underscore). The password they get is temporary: they choose their own at the first sign-in.
 */
public record StaffRequest(
        @NotBlank @Pattern(regexp = "^[a-z0-9._-]{3,50}$") String username,
        @NotBlank @Size(max = 80) String displayName,
        @NotNull Role role,
        @Nullable Boolean enabled,
        @Nullable Long branchId) {
}
