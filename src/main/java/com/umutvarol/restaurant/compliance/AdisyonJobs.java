package com.umutvarol.restaurant.compliance;

import com.umutvarol.restaurant.business.BusinessScope;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Sends again the e-Adisyon versions that were not accepted, for every business in turn, each as itself. */
@Component
class AdisyonJobs {

    private final BusinessScope scope;
    private final AdisyonService adisyons;

    AdisyonJobs(BusinessScope scope, AdisyonService adisyons) {
        this.scope = scope;
        this.adisyons = adisyons;
    }

    @Scheduled(fixedDelayString = "${restaurant.fiscal.retry-interval:60s}", initialDelayString = "${restaurant.fiscal.retry-interval:60s}")
    void retryDue() {
        scope.forEach("e-adisyon retry", adisyons::retryDue);
    }
}
