package com.easyshop.cart.controller;

import com.easyshop.cart.CartServiceApplication;
// Jackson 3 (tools.jackson), which is what Boot 4's HTTP message converters
// use - the Jackson 2 JsonNode of the same name is also on the classpath and
// deserializes to nothing here.
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guest cart over real HTTP, through the real security chain.
 *
 * This is the half of the feature that unit-level tests cannot reach: whether
 * an ANONYMOUS request actually gets through. The service and repository have
 * supported guest carts since the previous ticket - what was missing, and what
 * is asserted here, is that SecurityConfig lets an unauthenticated caller reach
 * them while still refusing them everywhere else. Rule ORDER is the specific
 * thing at risk: put the guest permitAll after the /api/v1/cart/** CUSTOMER
 * rule and every test below still compiles, but anonymous guests get 403.
 *
 * Only this service's chain is exercised. The matching gateway permitAll is a
 * separate process and is verified by the verify-* scripts.
 */
@SpringBootTest(
        classes = CartServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "spring.data.redis.password=",
                "easyshop.cart.session-ttl=45d",
                "easyshop.cart.guest-ttl=3h"
        })
@Testcontainers
class GuestCartWebIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @LocalServerPort
    private int port;

    @Autowired
    private StringRedisTemplate redis;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void aGuestCanMintATokenAndShopWithoutEverAuthenticating() {
        String token = mintGuestToken();
        assertThat(token).isNotBlank();

        client.post().uri("/api/v1/cart/guest/items")
                .header(GuestCartController.TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(addItemBody(UUID.randomUUID(), 2))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);

        JsonNode cart = client.get().uri("/api/v1/cart/guest")
                .header(GuestCartController.TOKEN_HEADER, token)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK)
                .expectBody(JsonNode.class)
                .returnResult().getResponseBody();

        assertThat(cart.path("data").path("totalItems").asInt()).isEqualTo(2);
    }

    /**
     * The TTL assertion the ticket asks for, taken through the HTTP layer rather
     * than by calling the repository: it proves the controller reaches the GUEST
     * key-space, and so inherits the shorter guest expiry rather than the 45-day
     * session one.
     */
    @Test
    void aGuestCartCreatedOverHttpGetsTheShorterGuestTtl() {
        String token = mintGuestToken();

        client.post().uri("/api/v1/cart/guest/items")
                .header(GuestCartController.TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(addItemBody(UUID.randomUUID(), 1))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);

        Long ttlSeconds = redis.getExpire("cart:guest:" + token);
        assertThat(Duration.ofSeconds(ttlSeconds)).isCloseTo(Duration.ofHours(3), Duration.ofSeconds(30));
    }

    @Test
    void mintingATokenStoresNothing() {
        String token = mintGuestToken();

        // A bot hammering the mint endpoint must not be able to fill Redis with
        // empty carts - the key only appears once something is added to it.
        assertThat(redis.hasKey("cart:guest:" + token)).isFalse();
    }

    @Test
    void everyMintedTokenIsDistinct() {
        assertThat(mintGuestToken()).isNotEqualTo(mintGuestToken());
    }

    @Test
    void theSessionCartStillRejectsAnonymousCallers() {
        // The guest permitAll must not have widened the authenticated surface.
        client.get().uri("/api/v1/cart")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void mergingStillRequiresAnAuthenticatedUser() {
        // /api/v1/cart/merge sits under the same prefix as the guest paths, so
        // an over-broad permitAll pattern would silently expose it - and merging
        // is precisely the operation that writes into a REAL user's cart.
        client.post().uri("/api/v1/cart/merge")
                .header(GuestCartController.TOKEN_HEADER, UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aTokenThatTriesToInjectKeyStructureIsRejected() {
        client.get().uri("/api/v1/cart/guest")
                .header(GuestCartController.TOKEN_HEADER, "abc:def")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aMissingTokenHeaderIsRejected() {
        client.get().uri("/api/v1/cart/guest")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private String mintGuestToken() {
        JsonNode body = client.post().uri("/api/v1/cart/guest")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CREATED)
                .expectBody(JsonNode.class)
                .returnResult().getResponseBody();
        return body.path("data").path("token").asText();
    }

    private static String addItemBody(UUID productId, int quantity) {
        return """
                {"productId":"%s","name":"Mechanical Keyboard","price":129.99,"quantity":%d}"""
                .formatted(productId, quantity);
    }

    /**
     * application.yml points the resource server at a Keycloak issuer, and
     * resolving it would make this test depend on a running Keycloak. Supplying
     * a JwtDecoder bean makes Boot's auto-configured one back off. It is never
     * invoked - every test here is either anonymous or expects a 401 - but the
     * security chain will not build without one.
     */
    @TestConfiguration
    static class StubJwtDecoder {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException("no real tokens in this test");
            };
        }
    }
}
