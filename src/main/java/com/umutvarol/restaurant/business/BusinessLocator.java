package com.umutvarol.restaurant.business;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * Finds the business of a request nobody has signed in to, from something the request carries: a guest's QR or
 * payment token, a registered till's device token. Each feature that hands out such a token implements one, so the
 * business package does not depend on them. Lookups run across businesses ({@link BusinessContext#acrossAll}).
 */
public interface BusinessLocator {

    Optional<Long> locate(HttpServletRequest request);
}
