package com.umutvarol.restaurant.security.dto;

import com.umutvarol.restaurant.security.Role;

public record UserResponse(String username, String displayName, Role role) {
}
