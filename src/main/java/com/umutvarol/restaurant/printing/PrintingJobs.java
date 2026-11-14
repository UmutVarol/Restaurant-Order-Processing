package com.umutvarol.restaurant.printing;

import com.umutvarol.restaurant.business.BusinessScope;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends queued tickets to the printers, for every business in turn. Each run happens as that business, in its own transactions
 * (docs/adr/0001-multi-business.md), so one business's work never touches another's.
 */
@Component
class PrintingJobs {

    private final BusinessScope scope;
    private final PrintingService printing;

    PrintingJobs(BusinessScope scope, PrintingService printing) {
        this.scope = scope;
        this.printing = printing;
    }

    @Scheduled(fixedDelayString = "${restaurant.printing.poll-interval:PT2S}", initialDelayString = "PT5S")
    void print() {
        scope.forEach("printing", printing::printDue);
    }
}
