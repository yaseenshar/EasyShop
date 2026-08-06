package com.easyshop.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * How often this service is willing to re-fetch Keycloak's JWK Set.
 *
 * THE PROBLEM THIS BOUNDS. JWT validation here is local: the JWK Set (public
 * keys) is fetched once and cached, and every signature after that is verified
 * in-process without touching Keycloak. That holds only for tokens whose "kid"
 * header is already in the cached set. When a kid is NOT found, Nimbus treats it
 * as "the keys must have rotated" and forces an immediate re-fetch - and Spring
 * Security explicitly disables Nimbus's own rate limiter for that path
 * (NimbusJwtDecoder calls .rateLimited(false)).
 *
 * The consequence, measured rather than assumed (see JwksThrottleTest): ten
 * unauthenticated requests carrying random kid values produced TEN JWKS fetches.
 * Every anonymous caller could therefore turn one cheap HTTP request into one
 * Keycloak hit, on any of the six services that validate tokens - an
 * amplification attack against the identity provider that no amount of caching
 * downstream would have absorbed. The signature is garbage and the request 401s;
 * Keycloak has already been called by then.
 *
 * THE TRADE. Throttling forced re-fetches delays picking up a genuine key
 * rotation by at most this interval - during a rotation, tokens signed with the
 * brand-new key are rejected until the window passes. That is a bounded, seconds-
 * long cost on an operation that happens rarely, weighed against an unbounded
 * one available to anybody with curl. 30s matches Nimbus's own
 * DEFAULT_RATE_LIMIT_MIN_INTERVAL, i.e. the value its authors chose for exactly
 * this decision.
 */
@ConfigurationProperties(prefix = "easyshop.security.jwks")
public record JwksProperties(

        /**
         * Minimum time between JWK Set fetches. Within this window a forced
         * re-fetch is served from the last successful response instead of
         * calling Keycloak.
         */
        @DefaultValue("30s") Duration minRefreshInterval) {

    public JwksProperties {
        if (minRefreshInterval == null || minRefreshInterval.isNegative()) {
            throw new IllegalArgumentException(
                    "easyshop.security.jwks.min-refresh-interval must not be negative, but was "
                            + minRefreshInterval);
        }
    }
}
