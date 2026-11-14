package com.umutvarol.restaurant.business;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs background work once per business, as that business: a scheduled job sees and changes only one business's
 * data at a time, and a failure in one business does not stop the others.
 */
@Component
public class BusinessScope {

    private static final Logger log = LoggerFactory.getLogger(BusinessScope.class);

    private final BusinessRepository businesses;

    BusinessScope(BusinessRepository businesses) {
        this.businesses = businesses;
    }

    public void forEach(String job, Runnable work) {
        List<Long> ids = BusinessContext.acrossAll(() -> businesses.findByStatusNotOrderByIdAsc(Business.Status.CANCELLED))
                .stream().map(Business::getId).toList();
        for (Long id : ids) {
            try {
                BusinessContext.run(id, work);
            } catch (RuntimeException failure) {
                log.error("{} failed for business {}", job, id, failure);
            }
        }
    }
}
