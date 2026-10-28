package com.umutvarol.restaurant.channel.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * An order as a platform (or the integrator in front of it) sends it to our webhook. Each platform's own format is
 * translated into this one before it is signed and sent.
 *
 * @param id    the platform's order id; the same id is never placed twice
 * @param total what the guest paid the platform, tax included; checked against what our menu charges
 */
public record ChannelOrderPayload(
        @NotBlank @Size(max = 80) String id,
        @NotNull @Valid Customer customer,
        @NotEmpty @Size(max = 50) List<@NotNull @Valid Item> items,
        @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal total,
        @Size(max = 200) String note) {

    public record Customer(@NotBlank @Size(max = 60) String name, @Size(max = 30) String phone, @Size(max = 255) String address) {
    }

    /** @param options "Group: Choice", the way the platform shows them, e.g. "Acılık: Az acılı" */
    public record Item(
            @NotBlank @Size(max = 80) String id,
            @NotBlank @Size(max = 120) String name,
            @Min(1) @Max(50) int quantity,
            @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal unitPrice,
            @Size(max = 20) List<@NotBlank @Size(max = 90) String> options,
            @Size(max = 140) String note) {
    }
}
