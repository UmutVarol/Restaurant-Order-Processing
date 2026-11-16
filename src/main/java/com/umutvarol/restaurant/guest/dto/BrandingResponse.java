package com.umutvarol.restaurant.guest.dto;

import org.jspecify.annotations.Nullable;

/**
 * How the business looks to its guests.
 *
 * @param accent      {@code #rrggbb}, or null for Sofra's own colour; guests' screens keep text on it readable
 * @param logoVersion the logo's number, to put in its address; null when there is no logo
 */
public record BrandingResponse(@Nullable String accent, @Nullable Integer logoVersion) {

    public static final BrandingResponse NONE = new BrandingResponse(null, null);
}
