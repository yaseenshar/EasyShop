package com.easyshop.gateway.config; // adjust to the gateway's actual package

import java.net.InetSocketAddress;
import java.security.Principal;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ServerWebExchange;

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
 * ANONYMOUS CALLERS ARE KEYED BY IP. They used to collapse onto the single
 * literal key "anonymous", which put every unauthenticated request on the
 * planet into ONE shared bucket (10/s, burst 20). That is not really a rate
 * limit - it is a global cap, and one busy crawler could exhaust it and 429
 * anonymous catalog browsing for everybody. It became actively dangerous with
 * guest carts, which let an anonymous caller CREATE Redis keys: the shared
 * bucket both failed to contain an abuser and let that abuser deny service to
 * legitimate shoppers. Per-IP keys contain the damage to the abusing address.
 *
 * Keep the bean name aligned with the RequestRateLimiter key-resolver
 * reference in application.yml.
 */
@Configuration
public class RateLimiterConfig {

    /** Prefix so an IP key can never collide with a JWT "sub" principal name. */
    private static final String ANONYMOUS_KEY_PREFIX = "anon:";

    private static final String UNRESOLVED_ANONYMOUS_KEY = ANONYMOUS_KEY_PREFIX + "unknown";

    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(Principal::getName)
                .switchIfEmpty(Mono.fromSupplier(() -> anonymousKey(exchange)));
    }

    /**
     * Deliberately reads the transport-level peer address rather than the
     * X-Forwarded-For header.
     *
     * Trusting XFF blindly would INVERT the protection this provides: the
     * header is client-controlled, so an abuser could stamp a different value
     * on every request, mint an unlimited number of buckets and bypass the
     * limiter entirely. Reading the real peer address cannot be spoofed.
     *
     * The trade is that behind an un-configured reverse proxy every client
     * shares the proxy's address and therefore one bucket. That degrades to
     * exactly the behaviour this replaced, so it is never worse than before -
     * it fails safe rather than fails open. When a proxy IS deployed in front,
     * the fix is to let the infrastructure establish the real client address
     * (Spring's ForwardedHeaderTransformer, or Gateway's
     * XForwardedRemoteAddressResolver with an explicit trusted-hop count) so
     * that getRemoteAddress() reports it here - never to start parsing the raw
     * header at this layer.
     */
    private static String anonymousKey(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return UNRESOLVED_ANONYMOUS_KEY;
        }
        return ANONYMOUS_KEY_PREFIX + remote.getAddress().getHostAddress();
    }
}
