package com.umutvarol.restaurant.common.error;

import java.util.Map;

/**
 * The caller is known but this action is refused for a reason the client can act on (an unregistered device, a
 * missing manager approval). Mapped to {@code 403} with the given code.
 */
public class AccessRefusedException extends DomainException {

    public AccessRefusedException(String code, String message) {
        super(code, message, Map.of());
    }

    public AccessRefusedException(String code, String message, Map<String, Object> parameters) {
        super(code, message, parameters);
    }
}
