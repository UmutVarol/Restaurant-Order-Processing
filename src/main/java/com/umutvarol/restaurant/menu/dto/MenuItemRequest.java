package com.umutvarol.restaurant.menu.dto;

import com.umutvarol.restaurant.menu.DietaryTag;
import com.umutvarol.restaurant.menu.MenuCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

public record MenuItemRequest(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 255) String description,
        @NotNull MenuCategory category,
        @NotNull @DecimalMin("0.01") @DecimalMax("100000.00") @Digits(integer = 6, fraction = 2) BigDecimal price,
        @Size(max = 16) String icon,
        @Min(0) @Max(240) Integer prepTimeMinutes,
        Boolean popular,
        Set<DietaryTag> tags,
        @Size(max = 8) List<@Valid @NotNull OptionGroupRequest> optionGroups) {

    public int prepTimeMinutesOrDefault() {
        return prepTimeMinutes == null ? 0 : prepTimeMinutes;
    }

    public boolean popularOrDefault() {
        return Boolean.TRUE.equals(popular);
    }
}
