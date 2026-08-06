package com.easyshop.gateway.config;

import com.easyshop.common.security.JwksProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.reactive.JwkSetUriReactiveJwtDecoderBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * The gateway's half of the JWK Set fetch throttle.
 *
 * common-lib carries the servlet version for the six downstream services, but it
 * is deliberately @ConditionalOnWebApplication(SERVLET) and must not leak into
 * this reactive application - so the same policy is expressed here against
 * WebClient instead of RestOperations. The properties class IS shared, so both
 * stacks read one knob (easyshop.security.jwks.min-refresh-interval).
 *
 * This is the instance that matters most. The gateway is the internet-facing
 * one, so it sees the bogus-kid traffic first, and its reactive decoder is worse
 * off than the servlet one: Spring's ReactiveRemoteJWKSource re-fetches on an
 * unrecognised kid (its own comment says "Refresh the JWK set if the sought key
 * ID is not in the cached JWK set") and has no rate limiter available at all -
 * not even the disabled-by-default one Nimbus gives the servlet path.
 *
 * Worth stating plainly: the gateway's RequestRateLimiter does NOT cover this.
 * That filter runs as part of route handling, which happens after the security
 * WebFilter chain has already decoded the token - so the Keycloak fetch is
 * provoked before any rate limiting is consulted.
 */
@Configuration
@EnableConfigurationProperties(JwksProperties.class)
public class JwksThrottleConfig {

    private static final Logger log = LoggerFactory.getLogger(JwksThrottleConfig.class);

    @Bean
    public JwkSetUriReactiveJwtDecoderBuilderCustomizer throttledJwkSetFetchCustomizer(
            JwksProperties properties) {
        WebClient throttled = WebClient.builder()
                .filter(new JwkSetThrottleFilter(properties.minRefreshInterval().toMillis()))
                .build();
        return builder -> builder.webClient(throttled);
    }

    /**
     * Serves the last successful JWK Set body when the same URI is re-requested
     * inside the window.
     *
     * The body has to be buffered into a String rather than the ClientResponse
     * being cached directly: a response body is a one-shot stream, so a cached
     * ClientResponse would hand its second reader an already-consumed publisher.
     * Buffering is safe here because a JWK Set is a small, bounded document.
     *
     * Only 2xx responses are remembered, and the window runs from the last
     * success - so a Keycloak error is never pinned in place, and a gateway that
     * has not yet fetched successfully keeps retrying instead of locking itself
     * out of validating every token.
     */
    private static final class JwkSetThrottleFilter implements ExchangeFilterFunction {

        private final long minIntervalMillis;

        private URI cachedUri;
        private String cachedBody;
        private String cachedContentType;
        private long lastSuccessAt;

        private JwkSetThrottleFilter(long minIntervalMillis) {
            this.minIntervalMillis = minIntervalMillis;
        }

        @Override
        public Mono<ClientResponse> filter(org.springframework.web.reactive.function.client.ClientRequest request,
                                           org.springframework.web.reactive.function.client.ExchangeFunction next) {
            URI uri = request.url();

            synchronized (this) {
                if (cachedBody != null && uri.equals(cachedUri) && withinWindow()) {
                    log.debug("Serving cached JWK Set for {} - within the {}ms refresh window",
                            uri, minIntervalMillis);
                    return Mono.just(cachedResponse());
                }
            }

            return next.exchange(request).flatMap(response -> response.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .map(body -> {
                        if (response.statusCode().is2xxSuccessful()) {
                            synchronized (this) {
                                cachedUri = uri;
                                cachedBody = body;
                                cachedContentType = response.headers().asHttpHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
                                lastSuccessAt = System.currentTimeMillis();
                            }
                        }
                        // Rebuilt rather than forwarded: the original body has
                        // just been consumed by bodyToMono above.
                        return rebuild(response.statusCode().value(), body,
                                response.headers().asHttpHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
                    }));
        }

        private synchronized ClientResponse cachedResponse() {
            return rebuild(200, cachedBody, cachedContentType);
        }

        private static ClientResponse rebuild(int status, String body, String contentType) {
            ClientResponse.Builder builder = ClientResponse.create(
                    org.springframework.http.HttpStatusCode.valueOf(status));
            if (contentType != null) {
                builder.header(HttpHeaders.CONTENT_TYPE, contentType);
            }
            // Content-Length is deliberately NOT copied - the rebuilt body is
            // written by the codec and a stale length header would corrupt it.
            return builder.body(body).build();
        }

        private boolean withinWindow() {
            return (System.currentTimeMillis() - lastSuccessAt) < minIntervalMillis;
        }
    }
}
