package com.easyshop.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

/**
 * REPLACES the Phase 1 RateLimiterConfig.
 *
 * WHY IT CHANGED: the old resolver read the "X-User-Id" request header,
 * which the hand-rolled AuthenticationFilter injected after parsing the
 * JWT. Deleting that filter (it used HMAC verification, incompatible with
 * Keycloak's RS256) silently removed the header - so every request would
 * have fallen back to IP-based limiting without any error. A per-user rate
 * limit quietly degrading to per-IP is the kind of regression that only
 * shows up as a mystery in production behind a corporate NAT.
 *
 * Now the identity comes from the validated JWT in the reactive security
 * context, populated by the gateway's own resource-server filter chain -
 * one source of truth for identity, no header contract to keep in sync.
 */
@Configuration
public class RateLimiterConfig {

    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(auth -> auth instanceof JwtAuthenticationToken)
                .map(auth -> ((JwtAuthenticationToken) auth).getToken())
                .map(jwt -> "user:" + jwt.getSubject())
                // Anonymous traffic (public catalog/review reads) has no JWT -
                // fall back to IP. Note the well-known limitation: many users
                // behind one NAT share a bucket. Acceptable for anonymous
                // browsing; authenticated traffic gets precise per-user limits.
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    var addr = exchange.getRequest().getRemoteAddress();
                    return "ip:" + (addr == null ? "unknown" : addr.getAddress().getHostAddress());
                }));
    }
}