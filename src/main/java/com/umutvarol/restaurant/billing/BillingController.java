package com.umutvarol.restaurant.billing;

import com.umutvarol.restaurant.business.BillingInterval;
import com.umutvarol.restaurant.business.Business;
import com.umutvarol.restaurant.business.Business.Plan;
import com.umutvarol.restaurant.business.BusinessFilter;
import com.umutvarol.restaurant.common.error.AccessRefusedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** The owner's plan and subscription: what it includes, what is used, paying and managing the card. */
@Tag(name = "Billing", description = "Plan and subscription (Sofra Cloud)")
@RestController
class BillingController {

    record Offer(Plan plan, int monthlyCentsPerBranch, int yearlyCentsPerBranch, int minimumBranches,
                 PlanCatalog.Limits limits, Set<PlanCatalog.Feature> features, int billedBranches) {
    }

    record BillingResponse(Plan plan, Business.Status status, @Nullable Instant trialEndsAt, @Nullable Instant periodEndsAt,
                           @Nullable BillingInterval interval, @Nullable Integer billedBranches, @Nullable Instant pastDueSince,
                           int graceDays, boolean writable, SubscriptionService.Usage usage, PlanCatalog.Limits limits,
                           boolean billingEnabled, boolean portal, List<Offer> offers) {
    }

    record CheckoutRequest(@NotNull Plan plan, @NotNull BillingInterval interval) {
    }

    record Link(String url) {
    }

    private final SubscriptionService subscriptions;
    private final BillingProperties properties;

    BillingController(SubscriptionService subscriptions, BillingProperties properties) {
        this.subscriptions = subscriptions;
        this.properties = properties;
    }

    @Operation(summary = "Plan and subscription")
    @GetMapping("/api/v1/billing")
    BillingResponse summary(JwtAuthenticationToken auth) {
        requireOwner(auth);
        SubscriptionService.Summary summary = subscriptions.summary();
        Business b = summary.business();
        List<Offer> offers = Arrays.stream(Plan.values()).map(PlanCatalog::of).map(o -> new Offer(o.plan(),
                o.monthlyCentsPerBranch(), o.yearlyCentsPerBranch(), o.minimumBranches(), o.limits(), o.features(),
                PlanCatalog.billedBranches(o.plan(), summary.usage().openBranches()))).toList();
        return new BillingResponse(b.getPlan(), b.getStatus(), b.getTrialEndsAt(), b.getPeriodEndsAt(), b.getBillingInterval(),
                b.getBilledBranches(), b.getPastDueSince(), properties.graceDays(), summary.writable(), summary.usage(),
                summary.offer().limits(), summary.billingEnabled(), summary.portal(), offers);
    }

    @Operation(summary = "Pay for a plan", description = "Returns the provider's checkout page.")
    @PostMapping("/api/v1/billing/checkout")
    Link checkout(@Valid @RequestBody CheckoutRequest request, JwtAuthenticationToken auth) {
        requireOwner(auth);
        String back = ServletUriComponentsBuilder.fromCurrentContextPath().path("/").fragment("/billing").toUriString();
        return new Link(subscriptions.checkout(request.plan(), request.interval(), back, auth.getName()));
    }

    @Operation(summary = "Manage the subscription", description = "Returns the provider's page for the card and invoices.")
    @PostMapping("/api/v1/billing/portal")
    Link portal(JwtAuthenticationToken auth) {
        requireOwner(auth);
        return new Link(subscriptions.portal());
    }

    /** The subscription is the owner's: managers of one branch do not see or change it. */
    private static void requireOwner(JwtAuthenticationToken auth) {
        if (auth.getToken().getClaim(BusinessFilter.BRANCH_CLAIM) != null) {
            throw new AccessRefusedException("BILLING_OWNER_ONLY", "Only managers of all branches see the subscription");
        }
    }
}
