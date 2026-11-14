package com.umutvarol.restaurant.billing;

import com.umutvarol.restaurant.business.Business;
import com.umutvarol.restaurant.business.UsageLimits;
import com.umutvarol.restaurant.common.error.SubscriptionRequiredException;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Refuses the sixth active staff account, the sixteenth table or a second branch on the Starter plan. */
@Component
class PlanLimitsGuard implements UsageLimits {

    private final SubscriptionService subscriptions;

    PlanLimitsGuard(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @Override
    public void allowOneMore(Resource resource) {
        Business business = subscriptions.business();
        PlanCatalog.Limits limits = PlanCatalog.of(business.getPlan()).limits();
        int limit = switch (resource) {
            case BRANCHES -> limits.branches();
            case STAFF -> limits.activeStaff();
            case TABLES -> limits.tables();
        };
        if (limit == PlanCatalog.UNLIMITED) {
            return;
        }
        SubscriptionService.Usage usage = subscriptions.usage();
        int used = switch (resource) {
            case BRANCHES -> usage.openBranches();
            case STAFF -> usage.activeStaff();
            case TABLES -> usage.tables();
        };
        if (used >= limit) {
            throw new SubscriptionRequiredException("PLAN_LIMIT", "The %s plan includes %d %s".formatted(
                    business.getPlan(), limit, resource.name().toLowerCase()),
                    Map.of("plan", business.getPlan().name(), "resource", resource.name(), "limit", limit));
        }
    }
}
