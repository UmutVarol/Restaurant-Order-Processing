package com.umutvarol.restaurant.billing;

import com.umutvarol.restaurant.business.BranchesChangedEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Ends trials and grace periods on time, and keeps the billed number of branches in step. */
@Component
class BillingJobs {

    private final SubscriptionService subscriptions;

    BillingJobs(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @Scheduled(fixedDelayString = "${restaurant.billing.lapse-check:PT15M}", initialDelayString = "PT1M")
    void lapse() {
        subscriptions.lapse();
    }

    @TransactionalEventListener
    void onBranchesChanged(BranchesChangedEvent event) {
        subscriptions.branchesChanged();
    }
}
