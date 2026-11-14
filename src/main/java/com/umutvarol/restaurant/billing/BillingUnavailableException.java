package com.umutvarol.restaurant.billing;

import com.umutvarol.restaurant.common.error.UpstreamServiceException;

/** The billing provider could not be reached or refused; nothing changed. */
public class BillingUnavailableException extends UpstreamServiceException {

    public BillingUnavailableException(String message) {
        super("BILLING_UNAVAILABLE", message);
    }
}
