package com.umutvarol.restaurant.staff.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record TipPoolRequest(@NotNull LocalDate date) {
}
