package com.umutvarol.restaurant.common.error;

import java.util.Map;

/** A well-formed request breaks a business rule. Mapped to {@code 422 Unprocessable Content}. */
public class BusinessRuleException extends DomainException {

    public BusinessRuleException(String code, String message) {
        this(code, message, Map.of());
    }

    public BusinessRuleException(String code, String message, Map<String, Object> parameters) {
        super(code, message, parameters);
    }
}
