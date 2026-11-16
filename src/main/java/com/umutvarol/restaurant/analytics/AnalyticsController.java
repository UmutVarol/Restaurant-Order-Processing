package com.umutvarol.restaurant.analytics;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Analytics", description = "Sales reporting for managers (ADMIN)")
@RestController
@RequestMapping("/api/v1/analytics")
class AnalyticsController {

    private final AnalyticsService analyticsService;

    AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @Operation(summary = "Dashboard for the last N days",
            description = "KPIs with the previous period for comparison, daily and hourly series, category mix and top sellers.")
    @GetMapping("/dashboard")
    DashboardResponse dashboard(@RequestParam(defaultValue = "7") @Min(1) @Max(90) int days) {
        return analyticsService.dashboard(days);
    }

    @Operation(summary = "The branches side by side", description = "Chain plan; for managers of all branches.")
    @com.umutvarol.restaurant.business.RequiresFeature(com.umutvarol.restaurant.business.Feature.CHAIN)
    @GetMapping("/branches")
    BranchReport branches(@RequestParam(defaultValue = "7") @Min(1) @Max(90) int days,
                          org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken auth) {
        if (auth.getToken().getClaim(com.umutvarol.restaurant.business.BusinessFilter.BRANCH_CLAIM) != null) {
            throw new com.umutvarol.restaurant.common.error.AccessRefusedException("BRANCH_MANAGER_ONLY", "The branches side by side are for managers of all branches");
        }
        return analyticsService.branches(days);
    }
}
