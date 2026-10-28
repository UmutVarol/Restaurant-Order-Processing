package com.umutvarol.restaurant.menu.dto;

import com.umutvarol.restaurant.menu.OptionGroupSpec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
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
 * An option group in the menu editor, e.g. "Spice level", choose 1 of "Mild", "Medium", "Hot".
 *
 * @param minSelect 0 for optional groups; 1 or more makes the group required
 */
public record OptionGroupRequest(
        @NotBlank @Size(max = 60) String name,
        @NotNull @Min(0) @Max(12) Integer minSelect,
        @NotNull @Min(1) @Max(12) Integer maxSelect,
        @NotEmpty @Size(max = 12) List<@Valid @NotNull Choice> choices) {

    public record Choice(
            @NotBlank @Size(max = 60) String name,
            @DecimalMin("0.00") @DecimalMax("10000.00") @Digits(integer = 5, fraction = 2) BigDecimal priceDelta) {
    }

    public OptionGroupSpec toSpec() {
        return new OptionGroupSpec(name, minSelect, maxSelect, choices.stream()
                .map(choice -> new OptionGroupSpec.Choice(choice.name(),
                        choice.priceDelta() == null ? BigDecimal.ZERO : choice.priceDelta()))
                .toList());
    }
}
