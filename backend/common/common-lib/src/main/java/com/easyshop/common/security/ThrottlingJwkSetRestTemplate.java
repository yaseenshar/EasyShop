package com.easyshop.common.security;

import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;

/**
 * The RestOperations Spring Security uses to fetch the JWK Set, with a minimum
 * interval between fetches of the same URI.
 *
 * WHY HERE, AND NOT BY REPLACING THE JwtDecoder. Spring Boot builds the decoder
 * and attaches the validators that actually enforce security - the issuer check,
 * and on the gateway an audience check. Hand-rolling a replacement decoder means
 * re-implementing that assembly and keeping it in step with Boot forever; miss
 * the issuer validator once and tokens from any issuer whose signature happens
 * to verify are suddenly accepted. Boot exposes JwkSetUriJwtDecoderBuilder-
 * Customizer precisely so callers can adjust the transport without owning the
 * security decisions, and the JWK Set is fetched through exactly one call:
 * restOperations.exchange(request, String.class). Throttling that one call
 * leaves every validator Boot configures untouched.
 *
 * Two alternatives were measured and rejected (JwksThrottleTest keeps the
 * numbers honest):
 *   - Supplying a Spring Cache via builder.cache(..): still 10 fetches for 10
 *     bogus-kid tokens. It switches Nimbus's cache off and caches at a layer the
 *     forced re-fetch goes straight past.
 *   - Rebuilding the JWKSource with Nimbus's own .rateLimited(true): works
 *     (1 fetch), but requires NimbusJwtDecoder.withJwkSource and therefore the
 *     decoder replacement described above.
 *
 * ONLY SUCCESSFUL RESPONSES ARE CACHED, and the window runs from the last
 * success. A failing Keycloak therefore does not pin an error in place for the
 * whole interval, and a service that has never fetched successfully still
 * retries rather than locking itself out of validating anything.
 *
 * Thread-safety: reads and writes of the cached response are synchronized. The
 * critical section is a couple of field assignments; the HTTP call itself is
 * deliberately outside it, so a slow Keycloak cannot block every other request
 * thread.
 */
public class ThrottlingJwkSetRestTemplate extends RestTemplate {

    private static final Logger log = LoggerFactory.getLogger(ThrottlingJwkSetRestTemplate.class);

    private final long minIntervalMillis;

    private URI lastUri;
    private ResponseEntity<?> lastResponse;
    private long lastSuccessAt;

    public ThrottlingJwkSetRestTemplate(Duration minRefreshInterval) {
        this.minIntervalMillis = minRefreshInterval.toMillis();

        // Spring's default JWKS RestOperations is a package-private RestTemplate
        // that applies Nimbus's timeouts. Replacing it means re-applying them:
        // without a read timeout a hung Keycloak would hold this request thread
        // forever, which is a worse outage than the one being prevented.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(JWKSourceBuilder.DEFAULT_HTTP_CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(JWKSourceBuilder.DEFAULT_HTTP_READ_TIMEOUT);
        setRequestFactory(requestFactory);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> ResponseEntity<T> exchange(RequestEntity<?> requestEntity, Class<T> responseType) {
        URI uri = requestEntity.getUrl();

        synchronized (this) {
            if (lastResponse != null && uri.equals(lastUri) && withinWindow()) {
                // The common case under attack: a bogus kid forced a re-fetch we
                // already know cannot produce a new answer this soon.
                log.debug("Serving cached JWK Set for {} - within the {}ms refresh window",
                        uri, minIntervalMillis);
                return (ResponseEntity<T>) lastResponse;
            }
        }

        ResponseEntity<T> response = super.exchange(requestEntity, responseType);

        synchronized (this) {
            lastUri = uri;
            lastResponse = response;
            lastSuccessAt = System.currentTimeMillis();
        }
        return response;
    }

    private boolean withinWindow() {
        return (System.currentTimeMillis() - lastSuccessAt) < minIntervalMillis;
    }
}
