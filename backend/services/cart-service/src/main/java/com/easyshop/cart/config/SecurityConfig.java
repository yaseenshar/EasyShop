package com.easyshop.cart.config; // align with the service's actual config package

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * cart-service RBAC.
 *
 * DECISION: CUSTOMER-only. Vendors and admins have no cart; a SERVICE token has
 * no business here either. One role, one rule.
 *
 * OWNERSHIP IS STRUCTURAL, NOT ANNOTATED: the Redis cart key must be derived
 * from authentication.getName() (= the JWT sub claim) inside the controller /
 * service layer — NEVER from a client-supplied id in the path or body. With
 * that in place, someone else's cart is unreachable by construction, and no
 * @PreAuthorize ownership check is needed. Verify the controller actually does
 * this while installing — a path like /api/v1/cart/{userId} taking the id from
 * the URL would be an IDOR waiting to happen, and no URL rule below would
 * catch it.
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
                        // Prometheus scrapes anonymously - it presents no JWT - so
                        // without this the endpoint 401s and the service publishes
                        // nothing, however well instrumented it is. Metrics only:
                        // /actuator/env, /heapdump and friends stay behind
                        // authentication via the catch-all below.
                        //
                        // EXPOSURE NOTE: this makes operational data (URI templates,
                        // error counts, JVM internals, business counter values)
                        // readable by anyone who can reach the port. Acceptable here
                        // because these ports are only published for local dev; the
                        // production posture is management.server.port on a separate
                        // interface the internet cannot route to, scraped over the
                        // internal network.
                        .requestMatchers("/actuator/prometheus").permitAll()

                        // GUEST CARTS: anonymous by definition - a shopper who has
                        // not signed in has no token to present, so requiring one
                        // would defeat the feature. Authorization for these carts is
                        // possession of the unguessable X-Cart-Token (see
                        // GuestCartController), not a role.
                        //
                        // ORDER IS LOAD-BEARING: these two entries MUST precede the
                        // /api/v1/cart/** rule below. Spring Security applies the
                        // FIRST matching rule and stops - reversed, the CUSTOMER rule
                        // would swallow /api/v1/cart/guest/** and every anonymous
                        // request would 403. Both patterns are listed because
                        // /api/v1/cart/guest/** alone is not guaranteed to match the
                        // bare /api/v1/cart/guest used to mint a token.
                        .requestMatchers("/api/v1/cart/guest", "/api/v1/cart/guest/**").permitAll()

                        // Everything else on the cart API, INCLUDING /api/v1/cart/merge,
                        // still needs a customer: merging has to know whose account
                        // cart is the destination, and only a verified JWT can say.
                        .requestMatchers("/api/v1/cart/**").hasRole("CUSTOMER")

                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(rs -> rs.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }
}