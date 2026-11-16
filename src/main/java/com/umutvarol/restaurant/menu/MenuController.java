package com.umutvarol.restaurant.menu;

import com.umutvarol.restaurant.menu.dto.AvailabilityRequest;
import com.umutvarol.restaurant.menu.dto.MenuItemRequest;
import com.umutvarol.restaurant.menu.dto.MenuItemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Tag(name = "Menu", description = "Browse the menu (public) and manage it (ADMIN)")
@RestController
@RequestMapping("/api/v1/menu")
class MenuController {

    private final MenuService menuService;

    MenuController(MenuService menuService) {
        this.menuService = menuService;
    }

    @Operation(summary = "List menu items", description = """
            Public endpoint. Items are sorted by category, popularity and name, priced as the branch the request works in \
            sells them; central=true gives the central menu (what managers edit).""")
    @GetMapping
    List<MenuItemResponse> list(@RequestParam(required = false) MenuCategory category,
                                @RequestParam(required = false) String q,
                                @RequestParam(defaultValue = "true") boolean includeUnavailable,
                                @RequestParam(defaultValue = "false") boolean central) {
        return central ? menuService.list(category, q, includeUnavailable, null) : menuService.list(category, q, includeUnavailable);
    }

    @Operation(summary = "Get a single menu item")
    @GetMapping("/{id}")
    MenuItemResponse get(@PathVariable Long id) {
        return menuService.get(id);
    }

    @Operation(summary = "Create a menu item", description = "Requires ADMIN.")
    @PostMapping
    ResponseEntity<MenuItemResponse> create(@Valid @RequestBody MenuItemRequest request) {
        MenuItemResponse created = menuService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @Operation(summary = "Update a menu item", description = "Requires ADMIN. Existing orders keep the price they were placed with.")
    @PutMapping("/{id}")
    MenuItemResponse update(@PathVariable Long id, @Valid @RequestBody MenuItemRequest request) {
        return menuService.update(id, request);
    }

    @Operation(summary = "Mark an item as available or sold out", description = "Requires ADMIN.")
    @PatchMapping("/{id}/availability")
    MenuItemResponse changeAvailability(@PathVariable Long id, @Valid @RequestBody AvailabilityRequest request) {
        return menuService.changeAvailability(id, request.available());
    }

    record BranchPriceRequest(@jakarta.validation.constraints.DecimalMin("0.01") @jakarta.validation.constraints.Digits(integer = 8, fraction = 2)
                              java.math.BigDecimal price, Boolean available) {
    }

    @Operation(summary = "Each branch's own price and sale state of a dish", description = "Chain plan. Requires ADMIN.")
    @com.umutvarol.restaurant.business.RequiresFeature(com.umutvarol.restaurant.business.Feature.CHAIN)
    @GetMapping("/{id}/branches")
    List<MenuService.BranchPrice> branchPrices(@PathVariable Long id) {
        return menuService.branchPrices(id);
    }

    @Operation(summary = "Set one branch's own price and sale state of a dish",
            description = "Chain plan. A null price or availability goes back to the central menu. A branch manager sets only their own branch.")
    @com.umutvarol.restaurant.business.RequiresFeature(com.umutvarol.restaurant.business.Feature.CHAIN)
    @PutMapping("/{id}/branches/{branchId}")
    List<MenuService.BranchPrice> setBranchPrice(@PathVariable Long id, @PathVariable Long branchId, @Valid @RequestBody BranchPriceRequest request,
                                                 org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken auth) {
        Object own = auth.getToken().getClaim(com.umutvarol.restaurant.business.BusinessFilter.BRANCH_CLAIM);
        if (own != null && !own.toString().equals(branchId.toString())) {
            throw new com.umutvarol.restaurant.common.error.AccessRefusedException("BRANCH_NOT_YOURS", "A branch manager changes only their own branch");
        }
        return menuService.setBranchPrice(id, branchId, request.price(), request.available(), auth.getName());
    }

    @Operation(summary = "Archive a menu item", description = "Requires ADMIN. Soft delete — order history is preserved.")
    @DeleteMapping("/{id}")
    ResponseEntity<Void> archive(@PathVariable Long id) {
        menuService.archive(id);
        return ResponseEntity.noContent().build();
    }
}
