package com.umutvarol.restaurant.business;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** The current business's name, for guests' screens and payment descriptions. */
@Component
public class BusinessProfile {

    private final BusinessRepository businesses;
    private final Cache<Long, String> names = Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(Duration.ofMinutes(5)).build();

    BusinessProfile(BusinessRepository businesses) {
        this.businesses = businesses;
    }

    public String name(String fallback) {
        Long id = Optional.ofNullable(BusinessContext.current()).orElse(BusinessTenantResolver.SINGLE);
        String cached = names.getIfPresent(id);
        if (cached == null) {
            // read outside the cache's lock: a database call inside it would pin a virtual thread (docs/LOAD_TEST.md)
            cached = BusinessContext.acrossAll(() -> businesses.findById(id)).map(Business::getName).filter(n -> !"Sofra".equals(n) || id != 1L).orElse(fallback);
            names.put(id, cached);
        }
        return cached;
    }

    void forget(long id) {
        names.invalidate(id);
    }
}
