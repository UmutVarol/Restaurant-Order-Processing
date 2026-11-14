package com.umutvarol.restaurant.common.error;

import java.util.Map;

/**
 * The business's trial ended or its subscription lapsed: it can read, export and pay, but not change anything until
 * it pays. Mapped to {@code 402 Payment Required}.
 */
public class SubscriptionRequiredException extends DomainException {

    public SubscriptionRequiredException(String code, String message, Map<String, Object> parameters) {
        super(code, message, parameters);
    }
}
