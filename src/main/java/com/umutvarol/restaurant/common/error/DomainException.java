package com.umutvarol.restaurant.common.error;

import java.util.Map;

/**
 * Base type for errors raised by the domain. Each carries a stable, machine-readable {@code code}
 * (e.g. {@code COUPON_EXPIRED}) that clients can use for localisation, plus optional parameters.
 */
public abstract class DomainException extends RuntimeException {

    private final String code;
    private final transient Map<String, Object> parameters;

    protected DomainException(String code, String message, Map<String, Object> parameters) {
        super(message);
        this.code = code;
        this.parameters = Map.copyOf(parameters);
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }
}
