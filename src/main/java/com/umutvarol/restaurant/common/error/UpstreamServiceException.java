package com.umutvarol.restaurant.common.error;

import java.util.Map;

/**
 * A service we depend on, such as the payment provider, failed or could not be reached. Mapped to
 * {@code 502 Bad Gateway}; the client may try again.
 */
public class UpstreamServiceException extends DomainException {

    public UpstreamServiceException(String code, String message) {
        super(code, message, Map.of());
    }
}
