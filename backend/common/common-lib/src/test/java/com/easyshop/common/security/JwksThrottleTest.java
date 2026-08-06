package com.easyshop.common.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Counts sockets opened against a stand-in JWKS endpoint.
 *
 * Every assertion here is a number of HTTP calls, because that is the entire
 * subject: how much load a token reaching this service can put on Keycloak. A
 * mocked decoder cannot answer that - the caching and the forced-refresh
 * behaviour both live inside Nimbus and Spring, so the only honest test drives
 * the real decoder against a real socket and counts.
 */
class JwksThrottleTest {

    private static final AtomicInteger FETCHES = new AtomicInteger();

    private HttpServer server;
    private String jwkSetUri;
    private KeyPair originalKey;
    private final AtomicReference<String> servedJwks = new AtomicReference<>();

    @BeforeEach
    void startJwksEndpoint() throws Exception {
        FETCHES.set(0);
        originalKey = rsaKeyPair();
        servedJwks.set(jwksFor(originalKey, "key-1"));

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/certs", exchange -> {
            FETCHES.incrementAndGet();
            byte[] body = servedJwks.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        jwkSetUri = "http://localhost:" + server.getAddress().getPort() + "/certs";
    }

    @AfterEach
    void stopJwksEndpoint() {
        server.stop(0);
    }

    /**
     * The vulnerability this class exists to close, pinned as a baseline.
     *
     * Spring's stock decoder re-fetches the JWK Set every time a token carries a
     * "kid" it does not recognise, so an anonymous caller converts one cheap
     * request into one Keycloak hit. Asserted as "more than one" rather than an
     * exact count: the point is that the work is unbounded, and if a future
     * Spring or Nimbus upgrade fixes this upstream, THIS test failing is the
     * notification that the local throttle may no longer be needed.
     */
    @Test
    void stockDecoderRefetchesForEveryUnrecognisedKid() throws Exception {
        NimbusJwtDecoder stock = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        stock.decode(tokenSignedBy(originalKey, "key-1"));
        int afterWarmup = FETCHES.get();

        decodeBogusKidTokens(stock, 10);

        assertThat(FETCHES.get() - afterWarmup)
                .as("stock decoder: one JWKS fetch per bogus-kid request")
                .isGreaterThan(1);
    }

    @Test
    void throttledDecoderAbsorbsAnUnrecognisedKidStorm() throws Exception {
        NimbusJwtDecoder throttled = throttledDecoder(Duration.ofSeconds(30));
        throttled.decode(tokenSignedBy(originalKey, "key-1"));
        int afterWarmup = FETCHES.get();

        decodeBogusKidTokens(throttled, 10);

        assertThat(FETCHES.get() - afterWarmup)
                .as("throttled: bogus kids must not reach Keycloak at all inside the window")
                .isZero();
    }

    @Test
    void theFirstFetchIsNeverThrottled() throws Exception {
        NimbusJwtDecoder throttled = throttledDecoder(Duration.ofSeconds(30));

        throttled.decode(tokenSignedBy(originalKey, "key-1"));

        // A cold service must be able to fetch keys immediately, or it could
        // never validate anything.
        assertThat(FETCHES.get()).isEqualTo(1);
    }

    @Test
    void genuineTokensStillValidateAndAreServedFromCache() throws Exception {
        NimbusJwtDecoder throttled = throttledDecoder(Duration.ofSeconds(30));
        throttled.decode(tokenSignedBy(originalKey, "key-1"));
        int afterWarmup = FETCHES.get();

        for (int i = 0; i < 5; i++) {
            assertThat(throttled.decode(tokenSignedBy(originalKey, "key-1")).getSubject()).isNotBlank();
        }

        assertThat(FETCHES.get() - afterWarmup)
                .as("normal traffic must never touch Keycloak")
                .isZero();
    }

    /**
     * The cost of the throttle, made explicit and bounded.
     *
     * Throttling forced refreshes means a genuine key rotation is not picked up
     * instantly - tokens signed by the new key are rejected until the window
     * passes. This test proves that delay is temporary rather than permanent,
     * which is the difference between an acceptable trade and an outage: first
     * the new key is refused, then after the window the same token validates.
     */
    @Test
    void aRotatedKeyIsPickedUpOnceTheWindowPasses() throws Exception {
        // Keycloak rotates: the endpoint will serve a different key. Everything
        // expensive is prepared BEFORE the window opens - RSA key generation
        // routinely outlasts a short window, and doing it inside one makes this
        // test fail for timing reasons unrelated to the throttle.
        KeyPair rotatedKey = rsaKeyPair();
        String tokenFromNewKey = tokenSignedBy(rotatedKey, "key-2");
        String rotatedJwks = jwksFor(rotatedKey, "key-2");

        Duration window = Duration.ofSeconds(2);
        NimbusJwtDecoder throttled = throttledDecoder(window);
        throttled.decode(tokenSignedBy(originalKey, "key-1"));

        servedJwks.set(rotatedJwks);

        assertThatThrownBy(() -> throttled.decode(tokenFromNewKey))
                .as("inside the window the new key is not yet visible")
                .isInstanceOf(JwtException.class);

        Thread.sleep(window.toMillis() + 500);

        assertThat(throttled.decode(tokenFromNewKey).getSubject())
                .as("after the window the rotation is picked up without intervention")
                .isNotBlank();
    }

    private NimbusJwtDecoder throttledDecoder(Duration window) {
        return NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .restOperations(new ThrottlingJwkSetRestTemplate(window))
                .build();
    }

    private void decodeBogusKidTokens(NimbusJwtDecoder decoder, int count) throws Exception {
        int rejected = 0;
        for (int i = 0; i < count; i++) {
            try {
                decoder.decode(tokenSignedBy(originalKey, UUID.randomUUID().toString()));
            } catch (JwtException expected) {
                rejected++;
            }
        }
        // Sanity: the attack traffic must actually be rejected. If these ever
        // started succeeding, the fetch counts above would be measuring
        // something other than what this test claims.
        assertThat(rejected).isEqualTo(count);
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String jwksFor(KeyPair pair, String kid) {
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .keyID(kid)
                .algorithm(JWSAlgorithm.RS256)
                .build();
        return "{\"keys\":[" + jwk.toPublicJWK().toJSONString() + "]}";
    }

    private static String tokenSignedBy(KeyPair pair, String kid) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(),
                new JWTClaimsSet.Builder()
                        .subject(UUID.randomUUID().toString())
                        .issueTime(new Date())
                        .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                        .build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) pair.getPrivate()));
        return jwt.serialize();
    }
}
