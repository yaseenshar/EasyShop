package com.easyshop.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * REPLACES the hand-rolled AuthenticationFilter from Phase 1 - DELETE that
 * class (com.easyshop.gateway.filter.AuthenticationFilter) and remove every
 * "- name: AuthenticationFilter" line from application.yml routes.
 *
 * WHY: the old filter verified with Keys.hmacShaKeyFor(secret) - HMAC/
 * symmetric. Keycloak signs with RS256 - asymmetric. There is no shared
 * secret to verify against, so that filter rejected 100% of real Keycloak
 * tokens. It was written before Keycloak was adopted and never reconciled.
 *
 * This is the correct pattern: the gateway is itself an OAuth2 resource
 * server, fetching Keycloak's public keys via issuer-uri discovery exactly
 * like every downstream service. Note WebFlux types (ServerHttpSecurity,
 * SecurityWebFilterChain) - Spring Cloud Gateway is reactive; the servlet
 * HttpSecurity API used in user-service will not work here.
 *
 * Defense in depth: the gateway rejects bad tokens at the edge (fail fast,
 * no wasted downstream hop), and each service independently validates
 * again - never trust that a request arrived only via the expected path.
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/actuator/health", "/actuator/info").permitAll()
                        // Public browsing: catalog reads and review reads
                        .pathMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/v1/products/**", "/api/v1/categories/**",
                                "/api/v1/reviews/products/**").permitAll()
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));
        return http.build();
    }
}