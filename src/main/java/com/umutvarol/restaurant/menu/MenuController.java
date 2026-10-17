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

    @Operation(summary = "List menu items", description = "Public endpoint. Items are sorted by category, popularity and name.")
    @GetMapping
    List<MenuItemResponse> list(@RequestParam(required = false) MenuCategory category,
                                @RequestParam(required = false) String q,
                                @RequestParam(defaultValue = "true") boolean includeUnavailable) {
        return menuService.list(category, q, includeUnavailable);
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

    @Operation(summary = "Archive a menu item", description = "Requires ADMIN. Soft delete — order history is preserved.")
    @DeleteMapping("/{id}")
    ResponseEntity<Void> archive(@PathVariable Long id) {
        menuService.archive(id);
        return ResponseEntity.noContent().build();
    }
}
