package com.umutvarol.restaurant.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.umutvarol.restaurant.common.error.TooManyRequestsException;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Token-bucket rate limiting (Bucket4j) per client address, applied before authentication so that floods are
 * turned away as cheaply as possible. Every {@code /api/**} call draws from one budget; sign-in attempts, and
 * endpoints that need no sign-in at all (guest payments, provider callbacks, browser reports), also draw from
 * smaller ones of their own. Over budget, the client gets {@code 429} with {@code Retry-After}.
 * <p>
 * The client address is {@link HttpServletRequest#getRemoteAddr()}. Behind a reverse proxy, enable
 * {@code server.forward-headers-strategy} so that it is the real client and not the proxy; forwarding headers
 * are deliberately not trusted by default because any client can forge them.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class RateLimitFilter extends OncePerRequestFilter {

    static final String LOGIN_PATH = "/api/v1/auth/login";
    static final String PIN_LOGIN_PATH = "/api/v1/auth/pin-login";
    private static final List<String> PUBLIC_PREFIXES = List.of("/api/v1/public/", "/api/v1/payments/callback/",
            "/api/v1/payments/webhook/", "/api/v1/csp-reports");

    private final AbuseProtectionProperties.RateLimit limits;
    private final HandlerExceptionResolver resolver;
    private final MeterRegistry meters;
    private final Cache<String, Bucket> apiBuckets = buckets();
    private final Cache<String, Bucket> loginBuckets = buckets();
    private final Cache<String, Bucket> publicBuckets = buckets();

    RateLimitFilter(AbuseProtectionProperties properties, MeterRegistry meters,
                    @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.limits = properties.rateLimit();
        this.meters = meters;
        this.resolver = resolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !limits.enabled() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = request.getRemoteAddr();
        ConsumptionProbe probe = apiBuckets.get(client, key -> perMinute(limits.apiRequestsPerMinute()))
                .tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed() && isLogin(request)) {
            probe = loginBuckets.get(client, key -> perMinute(limits.loginRequestsPerMinute())).tryConsumeAndReturnRemaining(1);
        } else if (probe.isConsumed() && isPublic(request)) {
            probe = publicBuckets.get(client, key -> perMinute(limits.publicRequestsPerMinute())).tryConsumeAndReturnRemaining(1);
        }
        if (!probe.isConsumed()) {
            String endpoint = isLogin(request) ? "login" : isPublic(request) ? "public" : "api";
            meters.counter("sofra.security.rate_limited", "endpoint", endpoint).increment();
            resolver.resolveException(request, response, null, new TooManyRequestsException("RATE_LIMITED",
                    "Too many requests. Slow down and try again shortly.", Duration.ofNanos(probe.getNanosToWaitForRefill())));
            return;
        }
        response.setHeader("X-RateLimit-Remaining", Long.toString(probe.getRemainingTokens()));
        chain.doFilter(request, response);
    }

    private static boolean isPublic(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return PUBLIC_PREFIXES.stream().anyMatch(uri::startsWith);
    }

    private static boolean isLogin(HttpServletRequest request) {
        return "POST".equals(request.getMethod())
                && (LOGIN_PATH.equals(request.getRequestURI()) || PIN_LOGIN_PATH.equals(request.getRequestURI()));
    }

    /** Refills gradually over the minute rather than all at once, so a client cannot burst twice the budget. */
    private static Bucket perMinute(int requests) {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(requests).refillGreedy(requests, Duration.ofMinutes(1)))
                .build();
    }

    /** Bounded and self-expiring, so a flood from many addresses cannot grow memory without limit. */
    private static Cache<String, Bucket> buckets() {
        return Caffeine.newBuilder().maximumSize(100_000).expireAfterAccess(Duration.ofMinutes(5)).build();
    }
}
