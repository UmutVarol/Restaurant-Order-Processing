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

    /**
     * {@code businessCodeNeeded}: several businesses share this server and the address names none of them;
     * {@code billing}: this server sells subscriptions (Sofra Cloud).
     */
    record PublicConfig(String restaurantName, boolean demo, boolean businessCodeNeeded, boolean signupEnabled, boolean billing) {
    }

    private final RestaurantProperties restaurant;
    private final DemoProperties demo;
    private final com.umutvarol.restaurant.business.BusinessProfile profile;
    private final boolean signup;
    private final boolean billing;

    PublicConfigController(RestaurantProperties restaurant, DemoProperties demo,
                           com.umutvarol.restaurant.business.BusinessProfile profile,
                           @org.springframework.beans.factory.annotation.Value("${restaurant.cloud.signup-enabled:false}") boolean signup,
                           @org.springframework.beans.factory.annotation.Value("${restaurant.billing.provider:NONE}") String billing) {
        this.signup = signup;
        this.billing = !"NONE".equalsIgnoreCase(billing);
        this.restaurant = restaurant;
        this.demo = demo;
        this.profile = profile;
    }

    @Operation(summary = "Sign-in screen settings")
    @SecurityRequirements
    @GetMapping("/api/v1/public/config")
    PublicConfig config() {
        // the business the address or business code names; without one, nobody's name is given away
        boolean known = com.umutvarol.restaurant.business.BusinessContext.current() != null;
        return new PublicConfig(known ? profile.name(restaurant.name()) : null, demo.enabled(), !known, signup, billing);
    }
}
