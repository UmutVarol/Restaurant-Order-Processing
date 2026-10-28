package com.umutvarol.restaurant.printing.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;

public record PrinterRequest(
        @NotBlank @Size(max = 40) String name,
        @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9.:-]+") String host,
        @Min(1) @Max(65535) int port,
        @NotNull @Pattern(regexp = "58|80") String paperWidth,
        @NotEmpty @Size(max = 10) Set<@NotNull @Pattern(regexp = "[A-Z][A-Z0-9_]{1,19}") String> stations,
        boolean enabled) {
}
