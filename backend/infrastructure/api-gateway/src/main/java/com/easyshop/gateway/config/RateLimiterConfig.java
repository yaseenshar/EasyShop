package com.easyshop.gateway.config; // adjust to the gateway's actual package

import java.security.Principal;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import reactor.core.publisher.Mono;

/**
 * Rate-limit key resolution, generic over how authentication happened.
 *
 * The previous resolver read the JWT principal from the reactive security
 * context — correct for bearer requests, but session-authenticated (BFF)
 * requests carry an OAuth2AuthenticationToken, not a JwtAuthenticationToken.
 * Resolving via Principal#getName covers both.
 *
 * Bucket-consistency invariant: bearer principals name themselves by the "sub"
 * claim; session principals by user-name-attribute. application.yml pins
 * user-name-attribute to "sub" so the SAME human maps to ONE bucket on both
 * paths. Changing that attribute silently splits the buckets — the same
 * failure mode as the old X-User-Id header bug (handoff §6.1): no error,
 * just quietly weaker limiting.
 *
 * Keep the bean name aligned with the RequestRateLimiter key-resolver
 * reference in application.yml.
 */
@Configuration
public class RateLimiterConfig {

    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(Principal::getName)
                .switchIfEmpty(Mono.just("anonymous"));
    }
}