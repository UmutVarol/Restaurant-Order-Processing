package com.umutvarol.restaurant.payment.dto;

import jakarta.validation.constraints.NotNull;

public record SimulatedDecisionRequest(@NotNull Boolean approve) {
}
