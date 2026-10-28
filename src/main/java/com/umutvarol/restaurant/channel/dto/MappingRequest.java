package com.umutvarol.restaurant.channel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MappingRequest(@NotBlank @Size(max = 120) String externalName, @NotNull Long menuItemId) {
}
