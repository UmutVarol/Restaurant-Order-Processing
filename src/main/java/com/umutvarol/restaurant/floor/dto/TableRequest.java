package com.umutvarol.restaurant.floor.dto;

import com.umutvarol.restaurant.table.TableZone;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** A table's number (as printed on it and on its QR card), its seats and its area. */
public record TableRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{1,10}$") String code,
        @Min(1) @Max(30) int seats,
        @NotNull TableZone zone) {
}
