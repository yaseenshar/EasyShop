package com.easyshop.catalog.config; // align with the service's actual config package

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * catalog-service RBAC.
 *
 * PRODUCT DECISION (Option A chosen here): anonymous catalog browse. Standard
 * e-commerce posture — the catalog is the shop window. This REQUIRES the matching
 * permitAll at the api-gateway's reactive chain (see README §4), otherwise the
 * gateway 401s anonymous requests before they ever reach this service. If you
 * prefer Option B (auth-only browse), replace the GET permitAll with
 * .hasAnyRole("CUSTOMER", "VENDOR", "ADMIN") and change CATALOG_PUBLIC=false
 * when running verify-rbac.sh. Either answer is defensible if stated.
 *
 * KNOWN RBAC-ONLY LIMITATION, stated: until products carry a vendor_id, VENDOR
 * write access is fleet-wide — any vendor can edit any product. Role checks
 * cannot express ownership; that is resource-level authorization and needs a
 * schema migration (deferred ticket, README §6). Consequences drawn here:
 * DELETE stays ADMIN-only, because a vendor deleting arbitrary products is a
 * worse failure mode than a vendor editing them.
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
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()

                        // TODO: align patterns with your actual controllers.
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/products/**", "/api/v1/categories/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/products/**")
                        .hasAnyRole("VENDOR", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/products/**")
                        .hasAnyRole("VENDOR", "ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/products/**")
                        .hasRole("ADMIN")

                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(rs -> rs.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }
}