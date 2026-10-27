package com.umutvarol.restaurant.security;

import jakarta.servlet.DispatcherType;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy;
import org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

/**
 * Stateless JWT security. Access rules, from most to least specific:
 *
 * <table>
 *     <tr><th>Who</th><th>Can</th></tr>
 *     <tr><td>anyone</td><td>sign in, read the menu, open the web app, health and API docs</td></tr>
 *     <tr><td>any staff</td><td>view orders, the floor plan, coupons and live events; advance orders</td></tr>
 *     <tr><td>WAITER, ADMIN</td><td>price carts, place and cancel orders</td></tr>
 *     <tr><td>ADMIN</td><td>edit the menu, view analytics and actuator metrics</td></tr>
 * </table>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfig {

    private static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
            "font-src 'self' https://fonts.gstatic.com data:",
            "img-src 'self' data:",
            "connect-src 'self'",
            "frame-ancestors 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "object-src 'none'");

    /** Browser features the app never uses are switched off (OWASP HTTP Headers Cheat Sheet). */
    private static final String PERMISSIONS_POLICY = String.join(", ",
            "accelerometer=()", "camera=()", "display-capture=()", "geolocation=()", "gyroscope=()",
            "magnetometer=()", "microphone=()", "midi=()", "payment=()", "usb=()", "xr-spatial-tracking=()");

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemDetailsSecurityHandler problemHandler,
                                            JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
                // Bearer tokens are sent explicitly in a header, never automatically by the browser,
                // so cross-site request forgery is not possible and CSRF tokens are unnecessary.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**", "/favicon.svg",
                                "/manifest.webmanifest", "/robots.txt").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasRole(Role.ADMIN.name())
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/menu", "/api/v1/menu/**").permitAll()
                        .requestMatchers("/api/v1/menu", "/api/v1/menu/**").hasRole(Role.ADMIN.name())
                        .requestMatchers("/api/v1/analytics/**").hasRole(Role.ADMIN.name())
                        .requestMatchers(HttpMethod.POST, "/api/v1/orders", "/api/v1/orders/quote",
                                "/api/v1/orders/*/cancel").hasAnyRole(Role.ADMIN.name(), Role.WAITER.name())
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        // Sent only over HTTPS, as the HSTS specification requires.
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(Duration.ofDays(365).toSeconds()))
                        .crossOriginOpenerPolicy(coop -> coop.policy(CrossOriginOpenerPolicy.SAME_ORIGIN))
                        .crossOriginResourcePolicy(corp -> corp.policy(CrossOriginResourcePolicy.SAME_ORIGIN))
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", PERMISSIONS_POLICY)));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(AppUserRepository users) {
        return username -> users.findByUsernameIgnoreCase(username)
                .map(user -> User.withUsername(user.getUsername())
                        .password(user.getPasswordHash())
                        .roles(user.getRole().name())
                        .disabled(!user.isEnabled())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    JwtEncoder jwtEncoder(JwtProperties properties) {
        return NimbusJwtEncoder.withSecretKey(secretKey(properties)).algorithm(MacAlgorithm.HS256).build();
    }

    /**
     * Verifies every incoming token: HS256 only (the algorithm is fixed here, never read from the token), then
     * expiry, issuer, audience, explicit type {@code at+jwt}, a token ID, and that the token was not signed out.
     */
    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties, Clock clock, TokenDenylist denylist) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .validateType(false)
                .build();
        JwtTimestampValidator timestampValidator = new JwtTimestampValidator(Duration.ofSeconds(30));
        timestampValidator.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestampValidator,
                new JwtIssuerValidator(properties.issuer()),
                new JwtAudienceValidator(properties.audience()),
                new JwtTypeValidator(JwtTokenService.TOKEN_TYPE),
                new JwtClaimValidator<String>("jti", jti -> jti != null && !jti.isBlank()),
                notRevoked(denylist)));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> notRevoked(TokenDenylist denylist) {
        OAuth2Error revoked = new OAuth2Error("invalid_token", "The token has been revoked", null);
        return jwt -> denylist.isRevoked(jwt.getId())
                ? OAuth2TokenValidatorResult.failure(revoked)
                : OAuth2TokenValidatorResult.success();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(JwtTokenService.ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private static SecretKey secretKey(JwtProperties properties) {
        return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
