package com.easyshop.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Validates the bearer token against Keycloak's JWKS (via the ReactiveJwtDecoder
 * auto-configured from spring.security.oauth2.resourceserver.jwt.issuer-uri) and
 * forwards the decoded identity as trusted headers. Downstream services still
 * validate the JWT independently (defense in depth - see each service's
 * SecurityConfig), so this is a convenience/authorization-shortcut layer, not
 * the only gate.
 */
@Component
public class AuthenticationFilter extends AbstractGatewayFilterFactory<AuthenticationFilter.Config> {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationFilter.class);

    private final ReactiveJwtDecoder jwtDecoder;

    public AuthenticationFilter(ReactiveJwtDecoder jwtDecoder) {
        super(Config.class);
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String authHeader = exchange.getRequest()
                    .getHeaders()
                    .getFirst(HttpHeaders.AUTHORIZATION);

            // Guard clause: reject early if no bearer token
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return unauthorized(exchange);
            }

            String token = authHeader.substring(7);

            return jwtDecoder.decode(token)
                    .map(jwt -> {
                        List<String> roles = jwt.getClaimAsStringList("roles");
                        String email = jwt.getClaimAsString("email");

                        // Forward user identity as headers — downstream services trust these
                        // Never re-parse the JWT in downstream services; trust the gateway
                        return exchange.mutate()
                                .request(r -> {
                                    r.header("X-User-Id", jwt.getSubject());
                                    if (email != null) {
                                        r.header("X-User-Email", email);
                                    }
                                    if (roles != null && !roles.isEmpty()) {
                                        r.header("X-User-Roles", String.join(",", roles));
                                    }
                                })
                                .build();
                    })
                    .flatMap(chain::filter)
                    // Any decode failure (bad signature, expired, malformed) = 401.
                    // Logged at WARN, not silently swallowed - a bare 401 with no
                    // server-side trace is undebuggable in production.
                    .onErrorResume(ex -> {
                        log.warn("JWT rejected for {} {}", exchange.getRequest().getMethod(),
                                exchange.getRequest().getPath(), ex);
                        return unauthorized(exchange);
                    });
        };
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    public static class Config { }
}
