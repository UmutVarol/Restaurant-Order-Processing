package com.umutvarol.restaurant.security;

import com.umutvarol.restaurant.common.error.AccessRefusedException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A session signed in with a password someone else chose (the installer's, or a manager's reset) can do nothing
 * but choose a new one, see who it is, and sign out. Not a bean: it runs inside the security chain, after the
 * token has been read.
 */
class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    static final Set<String> ALLOWED = Set.of("/api/v1/auth/password", "/api/v1/auth/me", "/api/v1/auth/logout");

    private final ProblemDetailsSecurityHandler problems;

    PasswordChangeRequiredFilter(ProblemDetailsSecurityHandler problems) {
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt
                && Boolean.TRUE.equals(jwt.getToken().getClaimAsBoolean(JwtTokenService.PASSWORD_CHANGE_CLAIM))
                && request.getRequestURI().startsWith("/api/")
                && !ALLOWED.contains(request.getRequestURI())) {
            problems.refuse(request, response, new AccessRefusedException("PASSWORD_CHANGE_REQUIRED",
                    "Choose your own password before you continue."));
            return;
        }
        chain.doFilter(request, response);
    }
}
