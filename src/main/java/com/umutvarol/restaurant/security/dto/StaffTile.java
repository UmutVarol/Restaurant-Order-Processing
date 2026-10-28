package com.umutvarol.restaurant.security.dto;

import com.umutvarol.restaurant.security.Role;

/** Who can switch in on a registered till: name and role only. */
public record StaffTile(String username, String displayName, Role role) {
}
