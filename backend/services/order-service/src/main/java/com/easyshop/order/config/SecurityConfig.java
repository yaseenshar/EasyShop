package com.easyshop.order.config; // align with the service's actual config package

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
 * order-service RBAC — the service with all three authorization layers:
 *
 *   1. URL rules (below): coarse role gates, including /internal/** -> SERVICE,
 *      which finally closes the ENFORCEMENT half of §8.2 #3. Until now, network
 *      topology (the gateway not routing /internal/**) was the only protection.
 *
 *   2. Method security: admin-or-owner reads —
 *        @PreAuthorize("hasRole('ADMIN') or @orderAccess.isOwner(#orderId, authentication.name)")
 *      (see OrderAccess.java; requires the -parameters compiler flag, README traps).
 *
 *   3. Query-scoped ownership (PREFERRED for plain customer reads):
 *        orderRepository.findByIdAndCustomerKeycloakId(orderId, authentication.getName())
 *            .orElseThrow(OrderNotFoundException::new)
 *      -> 404 for both "missing" and "not yours"; no existence leak, no SpEL.
 *
 * ⚠ SEQUENCING (silent-failure warning): the moment /internal/** requires
 * SERVICE, review-service's Verified-Purchase call starts failing — and the
 * §4.12 circuit-breaker fallback HIDES it: every new review quietly lands
 * UNVERIFIED, zero errors anywhere. Land the review-service M2M token
 * propagation (§8.2 #3's other half) in the same change, or accept a documented
 * degradation window and keep verify-rbac.sh section [8] watching for it.
 * Silent-by-design failures need a test precisely because they are silent.
 *
 * DECISION: checkout is CUSTOMER-only. Admins act on orders through admin
 * surfaces; they do not purchase as ADMIN. Named audiences, not authenticated().
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

                        // Service-to-service surface — machine role only. Humans, including
                        // ADMIN, are deliberately excluded: an admin token leaking into logs
                        // must not be a skeleton key for internal APIs.
                        .requestMatchers("/internal/**").hasRole("SERVICE")

                        // POST /api/v1/orders IS checkout (see OrderController javadoc) -
                        // there is no separate /checkout sub-path.
                        .requestMatchers(HttpMethod.POST, "/api/v1/orders").hasRole("CUSTOMER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/orders").hasAnyRole("CUSTOMER", "ADMIN")
                        .requestMatchers("/api/v1/orders/**").hasAnyRole("CUSTOMER", "ADMIN")

                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(rs -> rs.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }
}