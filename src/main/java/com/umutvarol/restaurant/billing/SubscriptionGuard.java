package com.umutvarol.restaurant.billing;

import com.umutvarol.restaurant.business.BusinessContext;
import com.umutvarol.restaurant.common.error.SubscriptionRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * A business whose trial ended or whose subscription lapsed keeps reading, exporting, signing in and paying (its
 * own subscription, and guests their bills), but changes nothing until it pays: every other change is refused with
 * {@code 402 SUBSCRIPTION_INACTIVE}. Its data is never deleted for being unpaid.
 */
@Component
class SubscriptionGuard implements HandlerInterceptor {

    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");
    private static final List<PathPattern> ALWAYS_OPEN = parse(
            "/api/v1/auth/**", "/api/v1/billing/**", "/api/v1/public/billing/**", "/api/v1/public/signup/**",
            "/api/v1/csp-reports", "/api/v1/time-clock/out",
            // money already owed still comes in
            "/api/v1/payments/callback/*", "/api/v1/payments/webhook/*", "/api/v1/public/pay/**",
            "/api/v1/public/table/*/bill", "/api/v1/public/sim-checkout/*");

    private final SubscriptionService subscriptions;

    SubscriptionGuard(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (READS.contains(request.getMethod())) {
            return true;
        }
        Long business = BusinessContext.current();
        if (business == null) {
            return true;
        }
        PathContainer path = PathContainer.parsePath(request.getRequestURI().substring(request.getContextPath().length()));
        if (ALWAYS_OPEN.stream().anyMatch(pattern -> pattern.matches(path))) {
            return true;
        }
        if (!subscriptions.writable(business)) {
            throw new SubscriptionRequiredException("SUBSCRIPTION_INACTIVE",
                    "The trial has ended or the subscription has lapsed: choose a plan to carry on", Map.of());
        }
        return true;
    }

    private static List<PathPattern> parse(String... patterns) {
        PathPatternParser parser = PathPatternParser.defaultInstance;
        return java.util.Arrays.stream(patterns).map(parser::parse).toList();
    }
}
