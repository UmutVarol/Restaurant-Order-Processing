package com.umutvarol.restaurant.security.dto;

import com.umutvarol.restaurant.security.Role;
import org.jspecify.annotations.Nullable;

/**
 * The signed-in user. {@code hasPin} tells the client whether quick switching is set up; {@code method} is how this
 * session signed in ({@code pwd} or {@code pin}); {@code totpEnabled} whether two-step sign-in is on, and
 * {@code mfaSetupRequired} whether this manager still has to turn it on; {@code mustChangePassword} whether the
 * password was chosen by someone else and has to be replaced before anything else; {@code branchId} the branch they
 * work in, none for those who work in all of a chain's branches.
 */
public record UserResponse(String username, String displayName, Role role, boolean hasPin, String method,
                           boolean totpEnabled, boolean mfaSetupRequired, boolean mustChangePassword,
                           @Nullable Long branchId) {
}
