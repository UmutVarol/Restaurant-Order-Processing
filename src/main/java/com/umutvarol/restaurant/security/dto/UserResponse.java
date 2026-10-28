package com.umutvarol.restaurant.security.dto;

import com.umutvarol.restaurant.security.Role;

/**
 * The signed-in user. {@code hasPin} tells the client whether quick switching is set up; {@code method} is how this
 * session signed in ({@code pwd} or {@code pin}).
 */
public record UserResponse(String username, String displayName, Role role, boolean hasPin, String method) {
}
