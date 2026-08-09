package com.easyshop.cart.metrics;

import com.easyshop.cart.CartServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scrapes /actuator/prometheus exactly the way Prometheus does - over HTTP, with
 * no credentials - and asserts on the text that comes back.
 *
 * WHY THIS IS AN HTTP TEST AND NOT A REGISTRY TEST. Asserting against an
 * injected MeterRegistry would have passed throughout the entire period this
 * fleet was publishing nothing: the meters existed in-process the whole time.
 * Three separate things had to be true for a single number to reach Prometheus -
 * a registry on the classpath, a scrape target configured, and the endpoint
 * reachable WITHOUT a JWT - and only the last one is visible from here. An
 * anonymous GET is the only assertion that covers all three.
 */
@SpringBootTest(
        classes = CartServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "spring.data.redis.password="
        })
@Testcontainers
class PrometheusScrapeIntegrationTest {

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

    private RestTestClient client() {
        return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private String scrape() {
        return client().get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK)
                .expectBody(String.class)
                .returnResult().getResponseBody();
    }

    /**
     * The regression that motivated the whole ticket: every service required a
     * JWT on this path, so the scraper got 401 and collected nothing.
     */
    @Test
    void theScrapeEndpointIsReachableWithoutCredentials() {
        assertThat(scrape()).isNotBlank();
    }

    @Test
    void theRestOfActuatorStillRequiresAuthentication() {
        // Opening the metrics path must not have opened /actuator/env, which
        // prints configuration - including property values - to whoever asks.
        client().get().uri("/actuator/env")
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status).isIn(HttpStatus.UNAUTHORIZED.value(), HttpStatus.NOT_FOUND.value()));
    }

    /**
     * Proves the common-lib MeterFilter reached this service: without it there
     * is no application tag, and every cross-service query has to fall back to
     * Prometheus' scrape-config "job" label.
     */
    @Test
    void everyMeterCarriesTheApplicationTag() {
        assertThat(scrape()).contains("application=\"cart-service\"");
    }

    /**
     * The latency buckets, without which histogram_quantile() has nothing to
     * work with and p95/p99 simply cannot be computed - only a mean, which is
     * the statistic that hides the outliers you care about.
     */
    @Test
    void httpLatencyBucketsArePublished() {
        // Generate one request so the timer exists.
        client().get().uri("/actuator/health").exchange();

        String body = scrape();
        assertThat(body).contains("http_server_requests_seconds_bucket");
        // The SLO boundaries configured in MetricsCommonAutoConfiguration.
        assertThat(body).contains("le=\"0.5\"");
    }

    /**
     * End-to-end proof for a BUSINESS meter: mint a guest cart over HTTP, then
     * see that exact counter in the scrape output. This is the chain the ticket
     * is about - code increments a counter, and something outside the process
     * can actually read it.
     */
    @Test
    void businessCountersReachTheScrapeOutput() {
        client().post().uri("/api/v1/cart/guest")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CREATED);

        // Note the exact published name. An earlier version of this meter was
        // called easyshop.carts.guest.created and arrived as
        // easyshop_carts_guest_total, because the Prometheus client reserves the
        // _created suffix - see GuestCartController. Asserting the wire name is
        // what caught that; a registry-level assertion would not have.
        assertThat(scrape())
                .contains("easyshop_carts_guest_issued_total")
                .contains("application=\"cart-service\"");
    }

    /**
     * CARDINALITY GUARD. The uri label must carry the TEMPLATE
     * (/api/v1/cart/guest/items/{productId}), never the resolved path. If Spring
     * ever stopped templating it, every product id a shopper touched would mint
     * a fresh time series - unbounded growth that surfaces as Prometheus slowly
     * exhausting memory rather than as anything that looks like a bug here.
     */
    @Test
    void requestUrisAreTemplatedRatherThanRaw() {
        UUID productId = UUID.randomUUID();
        String token = UUID.randomUUID().toString();

        client().post().uri("/api/v1/cart/guest/items")
                .header("X-Cart-Token", token) // literal on purpose: this is the wire contract
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"productId":"%s","name":"Keyboard","price":10.00,"quantity":1}"""
                        .formatted(productId))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);

        client().delete().uri("/api/v1/cart/guest/items/" + productId)
                .header("X-Cart-Token", token)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);

        String body = scrape();
        assertThat(body)
                .as("a raw product id must never appear as a label value")
                .doesNotContain(productId.toString());
        assertThat(body).contains("uri=\"/api/v1/cart/guest/items/{productId}\"");
    }

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
