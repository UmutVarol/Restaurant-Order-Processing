package com.umutvarol.restaurant.payment;

import com.umutvarol.restaurant.business.BusinessFilter;
import com.umutvarol.restaurant.business.Feature;
import com.umutvarol.restaurant.business.RequiresFeature;
import com.umutvarol.restaurant.common.error.AccessRefusedException;
import com.umutvarol.restaurant.security.StepUpAuthentication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The business's own online payment account. Managers of all branches only; changes need a recent password. */
@Tag(name = "Payments", description = "Bills, payments and the day report")
@RequiresFeature(Feature.ONLINE_PAYMENTS)
@RestController
class PaymentSettingsController {

    record KeysRequest(@NotBlank @Size(max = 200) String apiKey, @NotBlank @Size(max = 200) String secretKey, boolean sandbox) {
    }

    private final PaymentSettingsService settings;
    private final StepUpAuthentication stepUp;

    PaymentSettingsController(PaymentSettingsService settings, StepUpAuthentication stepUp) {
        this.settings = settings;
        this.stepUp = stepUp;
    }

    @Operation(summary = "Online payment account", description = "Never returns a key.")
    @GetMapping("/api/v1/payment-settings")
    PaymentSettingsService.View view(JwtAuthenticationToken auth) {
        requireOwner(auth);
        return settings.view();
    }

    @Operation(summary = "Connect an iyzico account")
    @PutMapping("/api/v1/payment-settings")
    PaymentSettingsService.View save(@Valid @RequestBody KeysRequest request, JwtAuthenticationToken auth) {
        requireOwner(auth);
        stepUp.requireRecentSignIn(auth);
        return settings.save(request.apiKey(), request.secretKey(), request.sandbox(), auth.getName());
    }

    @Operation(summary = "Disconnect the account")
    @DeleteMapping("/api/v1/payment-settings")
    PaymentSettingsService.View remove(JwtAuthenticationToken auth) {
        requireOwner(auth);
        stepUp.requireRecentSignIn(auth);
        return settings.remove(auth.getName());
    }

    private static void requireOwner(JwtAuthenticationToken auth) {
        if (auth.getToken().getClaim(BusinessFilter.BRANCH_CLAIM) != null) {
            throw new AccessRefusedException("PAYMENT_SETTINGS_OWNER_ONLY", "Only managers of all branches connect payment accounts");
        }
    }
}
