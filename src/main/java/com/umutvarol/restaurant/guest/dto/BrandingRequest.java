package com.umutvarol.restaurant.guest.dto;

import jakarta.validation.constraints.Pattern;

/** @param accent {@code #rrggbb}; null goes back to Sofra's own colour */
public record BrandingRequest(@Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String accent) {
}
