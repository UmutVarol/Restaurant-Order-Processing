package com.umutvarol.restaurant.repricing;

import com.umutvarol.restaurant.business.Feature;
import com.umutvarol.restaurant.business.RequiresFeature;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Inflation tools", description = "Many prices changed at once, with a preview and an undo; dishes whose "
        + "ingredients take more of the price than the target.")
@RestController
@RequestMapping("/api/v1/repricing")
class RepricingController {

    record SettingsRequest(@NotNull @DecimalMin("5") @DecimalMax("90") BigDecimal targetFoodCost, @NotNull Rounding rounding) {
    }

    private final RepricingService repricing;

    RepricingController(RepricingService repricing) {
        this.repricing = repricing;
    }

    @Operation(summary = "What a bulk change would do", description = "Nothing changes. The answer's `expected` goes "
            + "back with the same request to apply it.")
    @PostMapping("/preview")
    RepricingService.Preview preview(@RequestBody RepricingService.Request request) {
        return repricing.preview(request);
    }

    @Operation(summary = "Apply a bulk change", description = "Refused (PRICES_CHANGED) if any price moved since the preview.")
    @PostMapping("/apply")
    RepricingService.ChangeView apply(@RequestBody RepricingService.Request request, Authentication authentication) {
        return repricing.apply(request, authentication.getName());
    }

    @Operation(summary = "The last bulk changes")
    @GetMapping("/changes")
    List<RepricingService.ChangeView> changes() {
        return repricing.recent();
    }

    @Operation(summary = "Take a bulk change back", description = "Prices changed again since are left as they are.")
    @PostMapping("/changes/{id}/revert")
    RepricingService.Reverted revert(@PathVariable Long id, Authentication authentication) {
        return repricing.revert(id, authentication.getName());
    }

    @Operation(summary = "Food cost of every dish with a recipe, against the target, with a suggested price")
    @RequiresFeature(Feature.STOCK)
    @GetMapping("/margins")
    RepricingService.Margins margins() {
        return repricing.margins();
    }

    @GetMapping("/settings")
    RepricingService.Settings settings() {
        return repricing.settings();
    }

    @PutMapping("/settings")
    RepricingService.Settings saveSettings(@Valid @RequestBody SettingsRequest request, Authentication authentication) {
        return repricing.saveSettings(request.targetFoodCost(), request.rounding(), authentication.getName());
    }
}
