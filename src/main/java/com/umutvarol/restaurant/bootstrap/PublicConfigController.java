package com.umutvarol.restaurant.bootstrap;

import com.umutvarol.restaurant.common.config.RestaurantProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the sign-in screen needs before anyone signs in: the restaurant's name, and whether this is the demo, whose
 * one-tap demo accounts are shown only then. Nothing else about the installation is told to strangers.
 */
@Tag(name = "Public")
@RestController
class PublicConfigController {

    record PublicConfig(String restaurantName, boolean demo) {
    }

    private final PublicConfig config;

    PublicConfigController(RestaurantProperties restaurant, DemoProperties demo) {
        this.config = new PublicConfig(restaurant.name(), demo.enabled());
    }

    @Operation(summary = "Sign-in screen settings")
    @SecurityRequirements
    @GetMapping("/api/v1/public/config")
    PublicConfig config() {
        return config;
    }
}
