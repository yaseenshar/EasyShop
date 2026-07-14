package com.easyshop.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * spring-boot-starter-oauth2-resource-server pulls in Spring Security, which
 * by default gates every request behind its own reactive filter chain. That's
 * not what we want here: authorization is a per-route decision made by the
 * gateway's own AuthenticationFilter (some routes are deliberately public -
 * catalog browsing, review reads). This chain steps out of the way and leaves
 * the ReactiveJwtDecoder bean (auto-configured from issuer-uri) available for
 * AuthenticationFilter to use directly.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .build();
    }
}
