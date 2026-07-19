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

                        // TODO: align pattern with your actual controllers.
                        .requestMatchers("/api/v1/cart/**").hasRole("CUSTOMER")

                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(rs -> rs.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }
}