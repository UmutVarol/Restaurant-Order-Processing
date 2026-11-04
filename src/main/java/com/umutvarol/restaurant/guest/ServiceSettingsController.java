package com.umutvarol.restaurant.guest;

import com.umutvarol.restaurant.guest.dto.ServiceSettingsRequest;
import com.umutvarol.restaurant.guest.dto.ServiceSettingsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Service mode", description = "Who takes the orders at the tables, and which carts a waiter checks first")
@RestController
@RequestMapping("/api/v1/service-settings")
class ServiceSettingsController {

    private final ServiceSettingsService settings;

    ServiceSettingsController(ServiceSettingsService settings) {
        this.settings = settings;
    }

    @Operation(summary = "The service mode and its rules")
    @GetMapping
    ServiceSettingsResponse get() {
        return settings.get();
    }

    @Operation(summary = "Change the service mode or its rules", description = "Managers only.")
    @PutMapping
    ServiceSettingsResponse update(@Valid @RequestBody ServiceSettingsRequest request, Authentication authentication) {
        return settings.update(request, authentication.getName());
    }
}
