package com.umutvarol.restaurant.business;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

/**
 * Tells Hibernate the current business, so every query on a business's data is limited to it and every insert is
 * stamped with it. On a single-restaurant installation, work outside a request (start-up, tests) belongs to business
 * 1; with Sofra Cloud on ({@code restaurant.cloud.enabled}) a query without a business fails instead of guessing.
 */
@Component
class BusinessTenantResolver implements CurrentTenantIdentifierResolver<Long>, HibernatePropertiesCustomizer {

    static final long SINGLE = 1L;

    private final boolean strict;
    /** Start-up work (accounts and demo data of the original business) runs before the app takes requests. */
    private volatile boolean ready;

    BusinessTenantResolver(@Value("${restaurant.cloud.enabled:false}") boolean cloud) {
        this.strict = cloud;
    }

    @Override
    public Long resolveCurrentTenantIdentifier() {
        Long current = BusinessContext.current();
        if (current != null) {
            return current;
        }
        if (strict && ready) {
            throw new IllegalStateException("No business for this work: refusing to read or write any business's data");
        }
        return SINGLE;
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    void ready() {
        this.ready = true;
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }

    @Override
    public boolean isRoot(Long tenantId) {
        return tenantId != null && tenantId == BusinessContext.ALL;
    }

    @Override
    public void customize(java.util.Map<String, Object> properties) {
        properties.put(org.hibernate.cfg.AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
    }
}
