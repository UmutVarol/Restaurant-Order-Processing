package com.umutvarol.restaurant.order.dto;

import com.umutvarol.restaurant.order.AdjustmentKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** Void or comp units of one line. {@code kind} is {@code VOID} or {@code COMP}. */
public record LineAdjustmentRequest(
        @NotNull AdjustmentKind kind,
        @Min(1) @Max(50) int quantity,
        @NotBlank @Size(max = 140) String reason,
        @Valid @Nullable ManagerApproval approval) {
}
