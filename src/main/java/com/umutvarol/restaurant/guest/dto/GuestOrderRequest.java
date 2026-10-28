package com.umutvarol.restaurant.guest.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** A guest's cart. Smaller limits than the till's: it comes from anyone holding the table's QR code. */
public record GuestOrderRequest(
        @Size(max = 40) @Nullable String guestName,
        @Size(max = 140) @Nullable String note,
        @NotEmpty @Size(max = 20) List<@Valid @NotNull Line> items) {

    public record Line(
            @NotNull Long menuItemId,
            @Min(1) @Max(20) int quantity,
            @Size(max = 140) @Nullable String note,
            @Size(max = 20) @Nullable List<@NotNull Long> optionIds) {
    }
}
