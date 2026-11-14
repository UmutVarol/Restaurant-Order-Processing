package com.umutvarol.restaurant.payment;

import com.umutvarol.restaurant.business.BusinessScope;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Settles online payments and retries refunds, for every business in turn. Each run happens as that business, in its own transactions
 * (docs/adr/0001-multi-business.md), so one business's work never touches another's.
 */
@Component
class PaymentJobs {

    private final BusinessScope scope;
    private final OnlinePaymentService payments;

    PaymentJobs(BusinessScope scope, OnlinePaymentService payments) {
        this.scope = scope;
        this.payments = payments;
    }

    @Scheduled(fixedDelayString = "${restaurant.payments.reconcile-interval:PT1M}", initialDelayString = "PT1M")
    void reconcile() {
        scope.forEach("payment reconciliation", payments::reconcilePendingPayments);
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT2M")
    void refunds() {
        scope.forEach("refund retry", payments::retryPendingRefunds);
    }
}
