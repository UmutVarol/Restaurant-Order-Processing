package com.umutvarol.restaurant.inventory;

import com.umutvarol.restaurant.inventory.dto.DishCostResponse;
import com.umutvarol.restaurant.inventory.dto.RecipeRequest;
import com.umutvarol.restaurant.inventory.dto.RecipeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Recipes", description = "What dishes are made of and what they cost (managers)")
@RestController
@RequestMapping("/api/v1/recipes")
class RecipeController {

    private final RecipeService recipes;

    RecipeController(RecipeService recipes) {
        this.recipes = recipes;
    }

    @Operation(summary = "Cost and food cost percentage of every dish with a recipe")
    @GetMapping("/costs")
    List<DishCostResponse> costs() {
        return recipes.costs();
    }

    @Operation(summary = "A dish's recipe")
    @GetMapping("/{menuItemId}")
    RecipeResponse recipe(@PathVariable Long menuItemId) {
        return recipes.recipe(menuItemId);
    }

    @Operation(summary = "Replace a dish's recipe", description = "Quantities are in grams, millilitres or pieces per portion.")
    @PutMapping("/{menuItemId}")
    RecipeResponse replace(@PathVariable Long menuItemId, @Valid @RequestBody RecipeRequest request) {
        return recipes.replace(menuItemId, request);
    }
}
