package com.umutvarol.restaurant.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** The whole recipe of a dish; it replaces the one before. An empty list removes it. */
public record RecipeRequest(@NotNull @Size(max = 40) List<@Valid @NotNull RecipeLineRequest> lines) {
}
