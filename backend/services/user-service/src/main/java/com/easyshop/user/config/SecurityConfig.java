package com.easyshop.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration for user-service as an OAuth2 Resource Server.
 *
 * Verified against Spring Security 7.0.4 documentation (github.com/spring-projects/
 * spring-security, docs path: servlet/oauth2/resource-server/jwt.adoc).
 *
 * Design decision: user-service trusts JWTs validated by either:
 *   (a) the gateway (which forwards X-User-Id etc. as trusted headers), OR
 *   (b) directly, if called in a context that bypasses the gateway (e.g. internal
 *       service-to-service calls or local testing).
 *
 * We configure full JWT validation HERE (not just header-trusting) because a
 * defense-in-depth principle applies: never assume a request reached you only
 * through the path you expect. Every service should be independently capable
 * of validating the token it receives, even if the gateway already did it once.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity // enables @PreAuthorize / @PostAuthorize on service methods
public class SecurityConfig {

    private final JwtAuthenticationConverter jwtAuthenticationConverter; // common-lib auto-config

    public SecurityConfig(JwtAuthenticationConverter jwtAuthenticationConverter) {
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Correct-for-production on a stateless resource server — unlike the
                // gateway's CSRF disable, which is a dev-only compromise (§7). The
                // distinction is worth being able to articulate.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()

                        // TODO: align patterns with your actual controllers before trusting this.
                        .requestMatchers("/api/v1/users/me", "/api/v1/users/me/**")
                        .hasAnyRole("CUSTOMER", "VENDOR", "ADMIN")
                        .requestMatchers("/api/v1/users/**").hasRole("ADMIN")

                        // Harden to .denyAll() once the patterns above are confirmed complete
                        // (§4.15 posture: every path not exposed is attack surface not defended).
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(rs -> rs.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }
}