package com.easyshop.gateway.config;

import com.easyshop.common.security.JwksProperties;
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
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

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
 * The reactive twin of common-lib's JwksThrottleTest.
 *
 * Worth having separately rather than trusting the servlet result: the reactive
 * decoder does NOT share the servlet one's plumbing. Spring hand-rolls
 * ReactiveRemoteJWKSource there, with its own refresh-on-unknown-kid and no rate
 * limiter of any kind - so "the servlet fix works" says nothing about the
 * gateway, which is the box actually exposed to the internet.
 */
class JwksThrottleConfigTest {

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

    @Test
    void stockReactiveDecoderRefetchesForEveryUnrecognisedKid() throws Exception {
        ReactiveJwtDecoder stock = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decode(stock, tokenSignedBy(originalKey, "key-1"));
        int afterWarmup = FETCHES.get();

        decodeBogusKidTokens(stock, 10);

        assertThat(FETCHES.get() - afterWarmup)
                .as("baseline: the gateway's decoder amplifies bogus kids into Keycloak calls")
                .isGreaterThan(1);
    }

    @Test
    void throttledReactiveDecoderAbsorbsAnUnrecognisedKidStorm() throws Exception {
        ReactiveJwtDecoder throttled = throttledDecoder(Duration.ofSeconds(30));
        decode(throttled, tokenSignedBy(originalKey, "key-1"));
        int afterWarmup = FETCHES.get();

        decodeBogusKidTokens(throttled, 10);

        assertThat(FETCHES.get() - afterWarmup).isZero();
    }

    @Test
    void genuineTokensStillValidateThroughTheRebuiltResponse() throws Exception {
        ReactiveJwtDecoder throttled = throttledDecoder(Duration.ofSeconds(30));

        // The filter buffers and rebuilds the JWKS response body. If that
        // rebuild were wrong - wrong content type, a stale Content-Length - the
        // key set would fail to parse and NOTHING would validate. This is the
        // test that catches that.
        assertThat(decode(throttled, tokenSignedBy(originalKey, "key-1")).getSubject()).isNotBlank();
        assertThat(decode(throttled, tokenSignedBy(originalKey, "key-1")).getSubject()).isNotBlank();
        assertThat(FETCHES.get()).isEqualTo(1);
    }

    @Test
    void aRotatedKeyIsPickedUpOnceTheWindowPasses() throws Exception {
        // Everything expensive happens BEFORE the window opens. RSA key
        // generation routinely takes longer than a short window, so doing it
        // between the warm-up and the assertion made this test flaky: the
        // window could expire during keygen, the new key would be fetched, and
        // the "still rejected" assertion would fail for a reason that has
        // nothing to do with the throttle.
        KeyPair rotatedKey = rsaKeyPair();
        String tokenFromNewKey = tokenSignedBy(rotatedKey, "key-2");
        String rotatedJwks = jwksFor(rotatedKey, "key-2");

        Duration window = Duration.ofSeconds(2);
        ReactiveJwtDecoder throttled = throttledDecoder(window);
        decode(throttled, tokenSignedBy(originalKey, "key-1"));

        servedJwks.set(rotatedJwks);

        assertThatThrownBy(() -> decode(throttled, tokenFromNewKey))
                .as("inside the window the new key is not yet visible")
                .isInstanceOf(JwtException.class);

        Thread.sleep(window.toMillis() + 500);

        assertThat(decode(throttled, tokenFromNewKey).getSubject())
                .as("after the window the rotation is picked up without intervention")
                .isNotBlank();
    }

    private ReactiveJwtDecoder throttledDecoder(Duration window) {
        var builder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri);
        new JwksThrottleConfig()
                .throttledJwkSetFetchCustomizer(new JwksProperties(window))
                .customize(builder);
        return builder.build();
    }

    private static org.springframework.security.oauth2.jwt.Jwt decode(ReactiveJwtDecoder decoder, String token) {
        return decoder.decode(token).block();
    }

    private void decodeBogusKidTokens(ReactiveJwtDecoder decoder, int count) throws Exception {
        int rejected = 0;
        for (int i = 0; i < count; i++) {
            try {
                decode(decoder, tokenSignedBy(originalKey, UUID.randomUUID().toString()));
            } catch (JwtException expected) {
                rejected++;
            }
        }
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
