package com.umutvarol.restaurant.common.error;

import java.util.Map;

/** An operation conflicts with the current state of a resource. Mapped to {@code 409 Conflict}. */
public class InvalidStateException extends DomainException {

    public InvalidStateException(String code, String message, Map<String, Object> parameters) {
        super(code, message, parameters);
    }
}
