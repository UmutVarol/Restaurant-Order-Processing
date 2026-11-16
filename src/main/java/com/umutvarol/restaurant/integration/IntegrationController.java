package com.umutvarol.restaurant.integration;

import com.umutvarol.restaurant.business.BusinessFilter;
import com.umutvarol.restaurant.business.BusinessProfile;
import com.umutvarol.restaurant.common.error.AccessRefusedException;
import com.umutvarol.restaurant.common.error.SubscriptionRequiredException;
import com.umutvarol.restaurant.security.StepUpAuthentication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The business's own e-Arşiv integrator and delivery platforms. Managers of all branches only; changes need a recent password. */
@Tag(name = "Integrations", description = "e-Arşiv and delivery platform connections of the business")
@RestController
class IntegrationController {

    record SaveRequest(@Size(max = 255) String baseUrl, @Size(max = 300) String apiKey, @Size(max = 300) String webhookSecret) {
    }

    private final IntegrationService integrations;
    private final BusinessProfile profile;
    private final StepUpAuthentication stepUp;

    IntegrationController(IntegrationService integrations, BusinessProfile profile, StepUpAuthentication stepUp) {
        this.integrations = integrations;
        this.profile = profile;
        this.stepUp = stepUp;
    }

    @Operation(summary = "The business's connections", description = "Never returns a key or secret.")
    @GetMapping("/api/v1/integrations")
    IntegrationService.View view(JwtAuthenticationToken auth) {
        requireOwner(auth);
        return integrations.view();
    }

    @Operation(summary = "Connect or change one", description = "A key or secret left empty keeps the saved one.")
    @PutMapping("/api/v1/integrations/{kind}")
    IntegrationService.View save(@PathVariable IntegrationKind kind, @Valid @RequestBody SaveRequest request, JwtAuthenticationToken auth) {
        requireOwner(auth);
        requirePlan(kind);
        stepUp.requireRecentSignIn(auth);
        return integrations.save(kind, request.baseUrl(), request.apiKey(), request.webhookSecret(), auth.getName());
    }

    @Operation(summary = "Disconnect one")
    @DeleteMapping("/api/v1/integrations/{kind}")
    IntegrationService.View remove(@PathVariable IntegrationKind kind, JwtAuthenticationToken auth) {
        requireOwner(auth);
        stepUp.requireRecentSignIn(auth);
        return integrations.remove(kind, auth.getName());
    }

    private void requirePlan(IntegrationKind kind) {
        if (!profile.includes(kind.feature())) {
            throw new SubscriptionRequiredException("PLAN_FEATURE", "This is not part of the " + profile.plan() + " plan",
                    Map.of("feature", kind.feature().name(), "plan", profile.plan().name()));
        }
    }

    private static void requireOwner(JwtAuthenticationToken auth) {
        if (auth.getToken().getClaim(BusinessFilter.BRANCH_CLAIM) != null) {
            throw new AccessRefusedException("INTEGRATIONS_OWNER_ONLY", "Only managers of all branches connect providers");
        }
    }
}
