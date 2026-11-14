package com.umutvarol.restaurant.fiscal;

import com.umutvarol.restaurant.business.BusinessScope;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retries legal documents, for every business in turn. Each run happens as that business, in its own transactions
 * (docs/adr/0001-multi-business.md), so one business's work never touches another's.
 */
@Component
class FiscalJobs {

    private final BusinessScope scope;
    private final FiscalService fiscal;

    FiscalJobs(BusinessScope scope, FiscalService fiscal) {
        this.scope = scope;
        this.fiscal = fiscal;
    }

    @Scheduled(fixedDelayString = "${restaurant.fiscal.retry-interval:60s}", initialDelayString = "${restaurant.fiscal.retry-interval:60s}")
    void retryDue() {
        scope.forEach("fiscal retry", fiscal::retryDue);
    }
}
