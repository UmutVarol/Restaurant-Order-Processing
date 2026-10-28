package com.umutvarol.restaurant.menu.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** {@code code} is set when a station is created and never changes; printers and dishes refer to it. */
public record StationRequest(
        @Pattern(regexp = "[A-Z][A-Z0-9_]{1,19}") String code,
        @NotBlank @Size(max = 40) String name,
        @Min(1) @Max(240) int targetMinutes) {
}
