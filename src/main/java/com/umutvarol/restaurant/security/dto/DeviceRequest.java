package com.umutvarol.restaurant.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeviceRequest(@NotBlank @Size(max = 60) String name) {
}
