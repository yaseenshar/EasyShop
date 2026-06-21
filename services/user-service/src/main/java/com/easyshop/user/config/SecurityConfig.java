package com.easyshop.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

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

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Stateless JWT auth has no concept of CSRF (no cookies/sessions to forge)
                .csrf(csrf -> csrf.disable())

                // No server-side session - every request is authenticated independently
                // via the bearer token. This is what makes horizontal scaling trivial:
                // any instance can handle any request with zero shared session state.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/api/v1/users/register").permitAll() // Keycloak webhook on user creation
                        .requestMatchers("/api/v1/users/**").hasAnyRole("CUSTOMER", "ADMIN")
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )

                // Verified Spring Security 7.0.4 DSL: .oauth2ResourceServer(oauth2 -> oauth2.jwt(...))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                );

        return http.build();
    }

    /**
     * Converts the 'roles' claim (populated by our Keycloak protocol mapper -
     * see realm export, "realm-roles-mapper") into Spring Security GrantedAuthority
     * objects prefixed with ROLE_, so .hasRole("ADMIN") works without the
     * default SCOPE_ prefix matching mismatch that trips up many first-time
     * Keycloak + Spring Security integrations.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(this::extractAuthorities);
        return converter;
    }

    private Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null) {
            return List.of();
        }
        return roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toList());
    }
}