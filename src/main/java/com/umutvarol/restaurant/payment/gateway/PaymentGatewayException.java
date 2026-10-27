package com.umutvarol.restaurant.payment.gateway;

/** The payment provider could not be reached, refused the request, or sent a response that failed verification. */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message) {
        super(message);
    }

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }

    /** The provider answered and said no. Unlike a timeout, trying the same request again will not help. */
    public static class Refused extends PaymentGatewayException {

        public Refused(String message) {
            super(message);
        }
    }
}
