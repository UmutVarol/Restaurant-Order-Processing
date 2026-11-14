package com.umutvarol.restaurant.business;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Decides which business a request works for, for its whole length (docs/adr/0001-multi-business.md):
 * a signed-in request by its token's {@code bid} claim (signed, so it cannot be changed); otherwise by a guest's or
 * till's token (BusinessLocator), the business code ({@code X-Business} header or {@code business} parameter), the
 * address ({@code slug.<base domain>}), or the only business there is. Runs inside the security chain, after the
 * token has been checked. Not a bean of its own: SecurityConfig places it.
 */
public class BusinessFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Business";
    public static final String CLAIM = "bid";

    private final BusinessRepository businesses;
    private final List<BusinessLocator> locators;
    private final String baseDomain;

    public BusinessFilter(BusinessRepository businesses, List<BusinessLocator> locators, String baseDomain) {
        this.businesses = businesses;
        this.locators = locators;
        this.baseDomain = baseDomain.toLowerCase(Locale.ROOT);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<Long> business = signedIn().or(() -> fromRequest(request));
        if (business.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        Long previous = BusinessContext.current();
        BusinessContext.set(business.get());
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) {
                BusinessContext.clear();
            } else {
                BusinessContext.set(previous);
            }
        }
    }

    private static Optional<Long> signedIn() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            Object claim = jwt.getToken().getClaim(CLAIM);
            // tokens issued before businesses existed belong to the original one
            return Optional.of(claim instanceof Number id ? id.longValue() : 1L);
        }
        return Optional.empty();
    }

    private Optional<Long> fromRequest(HttpServletRequest request) {
        for (BusinessLocator locator : locators) {
            Optional<Long> found = locator.locate(request);
            if (found.isPresent()) {
                return found;
            }
        }
        String code = Optional.ofNullable(request.getHeader(HEADER)).orElse(request.getParameter("business"));
        if (code == null && !baseDomain.isBlank()) {
            String host = Optional.ofNullable(request.getServerName()).orElse("").toLowerCase(Locale.ROOT);
            if (host.endsWith("." + baseDomain)) {
                code = host.substring(0, host.length() - baseDomain.length() - 1);
            }
        }
        if (code != null && !code.isBlank()) {
            return bySlug(code.strip().toLowerCase(Locale.ROOT));
        }
        // the businesses table is everyone's directory, read across businesses
        List<Business> all = BusinessContext.acrossAll(() -> businesses.findByStatusNotOrderByIdAsc(Business.Status.CANCELLED));
        return all.size() == 1 ? Optional.of(all.getFirst().getId()) : Optional.empty();
    }

    private Optional<Long> bySlug(String slug) {
        return slug.matches("[a-z0-9-]{2,40}")
                ? BusinessContext.acrossAll(() -> businesses.findBySlug(slug).map(Business::getId))
                : Optional.empty();
    }
}
