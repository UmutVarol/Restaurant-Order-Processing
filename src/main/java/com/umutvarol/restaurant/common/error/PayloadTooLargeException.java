package com.umutvarol.restaurant.common.error;

import java.io.IOException;

/**
 * Thrown while reading a request body that grows past the configured limit. It is an {@link IOException} so that
 * it can surface from an input stream, and the exception handler turns it into {@code 413 Content Too Large}.
 */
public class PayloadTooLargeException extends IOException {

    private final long limitBytes;

    public PayloadTooLargeException(long limitBytes) {
        super("Request body exceeds " + limitBytes + " bytes");
        this.limitBytes = limitBytes;
    }

    public long getLimitBytes() {
        return limitBytes;
    }
}
