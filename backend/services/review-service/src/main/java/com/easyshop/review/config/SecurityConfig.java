package com.easyshop.review.config; // align with the service's actual config package

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * review-service RBAC.
 *
 * READS mirror the catalog decision: reviews are part of the shop window, so
 * anonymous GET follows Option A (public browse). If catalog goes Option B,
 * flip reads here too — a half-public browse surface is the worst of both.
 * Public reads also require the matching gateway permitAll (README §4).
 *
 * WRITES: CUSTOMER-only — reviewing is a customer act. The Verified-Purchase
 * mechanism (§4.12) already handles WHICH customers reviewed credibly; RBAC
 * only gates the act itself.
 *
 * MODERATION: the state machine (§ moderation transitions) is an ADMIN surface.
 * Its IllegalStateException family now flows through GlobalExceptionHandler,
 * and its denials must come out as 403 — the AccessDeniedException handler
 * addition (common-lib/GlobalExceptionHandler-additions.java) is what keeps
 * a vendor poking a moderation endpoint from surfacing as a 500.
 *
 * Fine-grained follow-ups for method security, when needed:
 *   - edit/delete own review: query-scoped ownership by sub (order-service pattern)
 *   - vendor responses to reviews: a VENDOR-gated endpoint — meaningful only
 *     after vendor_id ownership lands (deferred, README §6).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationConverter jwtAuthenticationConverter; // common-lib auto-config

    public SecurityConfig(JwtAuthenticationConverter jwtAuthenticationConverter) {
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()

                        // Real controller paths are /api/v1/reviews/moderation/queue and
                        // /api/v1/reviews/moderation/{id}/approve|reject — no path segment
                        // between "reviews" and "moderation". Must be declared before the
                        // GET-permitAll/POST-CUSTOMER rules below or it fails open into them.
                        .requestMatchers("/api/v1/reviews/moderation/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/reviews/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/reviews").hasRole("CUSTOMER")
                        .requestMatchers("/api/v1/reviews/**").hasAnyRole("CUSTOMER", "ADMIN")

                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(rs -> rs.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }
}