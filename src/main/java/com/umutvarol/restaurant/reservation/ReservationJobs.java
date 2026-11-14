package com.umutvarol.restaurant.reservation;

import com.umutvarol.restaurant.business.BusinessScope;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Marks no-shows and erases old phone numbers, for every business in turn. Each run happens as that business, in its own transactions
 * (docs/adr/0001-multi-business.md), so one business's work never touches another's.
 */
@Component
class ReservationJobs {

    private final BusinessScope scope;
    private final ReservationService reservations;

    ReservationJobs(BusinessScope scope, ReservationService reservations) {
        this.scope = scope;
        this.reservations = reservations;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    void noShows() {
        scope.forEach("no-shows", reservations::markNoShows);
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    void contacts() {
        scope.forEach("contact erasure", () -> reservations.eraseOldContacts());
    }
}
