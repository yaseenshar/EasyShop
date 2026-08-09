package com.easyshop.cart.tracing;

import com.easyshop.cart.CartServiceApplication;
import io.opentelemetry.api.trace.Span;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts what a service does with an INBOUND traceparent header.
 *
 * Continuing an upstream trace is the half of propagation that is invisible in
 * a normal end-to-end check: if a service ignored the incoming header and
 * minted a fresh trace id, every individual service would still look perfectly
 * traced, and the only symptom would be that a caller's trace id never matches
 * anything downstream - which nobody notices until they are mid-incident trying
 * to follow one.
 *
 * A test-only filter echoes the trace id the SERVER is running in back as a
 * response header, which is the only way to observe the decision the server
 * made rather than inferring it from downstream effects.
 */
@SpringBootTest(
        classes = CartServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "spring.data.redis.password=",
                "management.tracing.sampling.probability=1.0",
                "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:1/v1/traces",
                "management.otlp.metrics.export.enabled=false"
        })
@Testcontainers
@org.springframework.context.annotation.Import(HttpTracePropagationIntegrationTest.TracingProbe.class)
class HttpTracePropagationIntegrationTest {

    private static final String TRACE_ID_HEADER = "X-Test-Trace-Id";
    private static final String UPSTREAM_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String UPSTREAM_SPAN_ID = "00f067aa0ba902b7";

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

    /**
     * Calls an endpoint that is ALREADY public (the guest cart mint) and reads
     * back the trace id the server was running in, echoed into a response
     * header by a test-only filter.
     *
     * Deliberately no dedicated probe endpoint: one would need its own
     * permitAll, and a test that has to relax authorization in order to observe
     * tracing ends up measuring its own scaffolding. A real, already-public
     * route keeps this on the same code path a genuine caller takes.
     */
    private String traceIdSeenByServer(String inboundTraceParent) {
        var request = client().post().uri("/api/v1/cart/guest");
        if (inboundTraceParent != null) {
            request = request.header("traceparent", inboundTraceParent);
        }
        return request.exchange()
                .expectStatus().isEqualTo(HttpStatus.CREATED)
                .returnResult(String.class)
                .getResponseHeaders()
                .getFirst(TRACE_ID_HEADER);
    }

    /**
     * The core guarantee: an upstream trace id survives into this service.
     */
    @Test
    void anInboundTraceparentIsContinuedRatherThanReplaced() {
        String seen = traceIdSeenByServer(
                "00-" + UPSTREAM_TRACE_ID + "-" + UPSTREAM_SPAN_ID + "-01");

        assertThat(seen)
                .as("the server must adopt the caller's trace id, not mint a new one")
                .isEqualTo(UPSTREAM_TRACE_ID);
    }

    /**
     * A request with no trace context still gets traced - it simply becomes the
     * root. Without this, a "propagation" fix that only ever continued existing
     * traces would leave every externally-originated request untraced.
     */
    @Test
    void aRequestWithNoTraceparentStartsItsOwnTrace() {
        String seen = traceIdSeenByServer(null);

        assertThat(seen).isNotBlank();
        assertThat(seen).hasSize(32).matches("[0-9a-f]{32}");
        assertThat(seen).isNotEqualTo(UPSTREAM_TRACE_ID);
    }

    /**
     * Two requests carrying the same upstream trace must both join it - this is
     * what makes a fan-out of calls appear under one trace rather than as
     * unrelated siblings.
     */
    @Test
    void everyRequestOnTheSameUpstreamTraceJoinsIt() {
        String traceParent = "00-" + UPSTREAM_TRACE_ID + "-" + UPSTREAM_SPAN_ID + "-01";

        assertThat(traceIdSeenByServer(traceParent)).isEqualTo(UPSTREAM_TRACE_ID);
        assertThat(traceIdSeenByServer(traceParent)).isEqualTo(UPSTREAM_TRACE_ID);
    }

    /**
     * A garbage header must not be adopted OR fatal: the request is served, and
     * the server falls back to starting its own trace. Propagation code that
     * throws on malformed input turns a header a stranger can set into a denial
     * of service.
     */
    @Test
    void aMalformedTraceparentIsIgnoredNotFatal() {
        String seen = traceIdSeenByServer("garbage-not-a-traceparent");

        assertThat(seen).isNotBlank();
        assertThat(seen).hasSize(32);
        assertThat(seen).isNotEqualTo(UPSTREAM_TRACE_ID);
    }

    /**
     * LOG CORRELATION. Boot's default log pattern contains
     * ${LOG_CORRELATION_PATTERN}, which the tracing auto-configuration fills in
     * with traceId/spanId - so this needs no configuration, only proof. Without
     * it you cannot pivot from an error line to the trace that produced it,
     * which is where tracing earns its keep day to day.
     *
     * Asserted by capturing what Logback actually emits, because the pattern is
     * assembled from three interacting properties and "it should be on by
     * default" is exactly the assumption that has been wrong three times here.
     */
    @Test
    void logsCarryTheTraceIdOfTheRequestBeingServed() {
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                        org.slf4j.Logger.ROOT_LOGGER_NAME);
        // EVERY event is captured, not only those already carrying a traceId.
        // Filtering on traceId up front would make "nothing was logged"
        // indistinguishable from "logging lost the trace" - the first version
        // of this test did exactly that and reported a failure it could not
        // actually attribute.
        var captured = new java.util.concurrent.CopyOnWriteArrayList<
                ch.qos.logback.classic.spi.ILoggingEvent>();
        var appender = new ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent>() {
            @Override
            protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
                captured.add(event);
            }
        };
        appender.setContext(root.getLoggerContext());
        appender.start();
        root.addAppender(appender);
        try {
            traceIdSeenByServer("00-" + UPSTREAM_TRACE_ID + "-" + UPSTREAM_SPAN_ID + "-01");
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }

        assertThat(captured)
                .as("the probe filter logs on every request, so something must have been captured")
                .isNotEmpty();
        assertThat(captured)
                .as("a log event emitted while serving the request must carry its traceId in the MDC, "
                        + "or you cannot get from an error line to its trace")
                .anySatisfy(event -> assertThat(event.getMDCPropertyMap())
                        .containsEntry("traceId", UPSTREAM_TRACE_ID));
    }

    /**
     * Echoes the trace context the SERVER is running in into a response header.
     *
     * A plain Filter bean defaults to the lowest precedence, so it runs INSIDE
     * Spring's observation filter and therefore inside the server span's scope -
     * the same context a controller would see.
     *
     * Test-only, registered by this test's configuration and never present in a
     * deployment: echoing internal trace ids back to arbitrary callers would let
     * someone correlate their own probing with your internals.
     */
    @TestConfiguration
    static class TracingProbe {

        /**
         * Registered explicitly rather than as a bare Filter bean: with Spring
         * Security present, a plain Filter bean did not reach the chain at all
         * (verified - the header was simply absent from the response). An
         * explicit registration with a stated order removes the ambiguity.
         *
         * LOWEST_PRECEDENCE puts it last, i.e. INSIDE Spring's observation
         * filter, so Span.current() is the server span the request is actually
         * running in rather than an empty context.
         */
        @Bean
        org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>
                traceIdEchoFilter() {
            var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>();
            registration.setFilter((request, response, chain) -> {
                ((jakarta.servlet.http.HttpServletResponse) response)
                        .setHeader(TRACE_ID_HEADER, Span.current().getSpanContext().getTraceId());
                // Emitted from inside the server span's scope so the log
                // correlation assertion has something deterministic to inspect -
                // the guest-cart endpoint itself logs nothing at INFO.
                org.slf4j.LoggerFactory.getLogger("trace-probe")
                        .info("serving request under active trace");
                chain.doFilter(request, response);
            });
            registration.addUrlPatterns("/*");
            registration.setOrder(org.springframework.core.Ordered.LOWEST_PRECEDENCE);
            return registration;
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException("no real tokens in this test");
            };
        }
    }
}
