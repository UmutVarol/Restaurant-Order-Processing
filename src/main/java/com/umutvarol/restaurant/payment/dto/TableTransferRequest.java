package com.umutvarol.restaurant.payment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** The table a seating moves to, or whose bill it joins. */
public record TableTransferRequest(@NotNull @Positive Long tableId) {
}
