package com.umutvarol.restaurant.push;

import java.net.URI;
import java.util.Map;

/** Hands an encrypted message to a push service; the HTTP status it answered (0 when it could not be reached). */
public interface PushTransport {

    int post(URI endpoint, Map<String, String> headers, byte[] body);
}
