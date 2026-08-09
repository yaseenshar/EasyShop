package com.easyshop.common.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.Level;
import net.logstash.logback.encoder.LogstashEncoder;
import net.logstash.logback.stacktrace.ShortenedThrowableConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the SHAPE of a structured log line.
 *
 * These field names are a contract, not an implementation detail: every Loki
 * query, dashboard panel and alert is written against them, and renaming one is
 * a silent break - the query simply returns nothing, which looks identical to
 * "the problem did not happen". Asserting the emitted JSON is what makes that
 * contract enforceable rather than aspirational.
 *
 * The encoder is exercised directly rather than through a Spring context: what
 * is under test is what LogstashEncoder produces for a given event, and a
 * booted application would add a container's worth of moving parts between the
 * assertion and the thing being asserted.
 */
class StructuredLoggingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LoggerContext context;
    private LogstashEncoder encoder;

    @BeforeEach
    void setUp() {
        context = new LoggerContext();
        context.putProperty("serviceName", "cart-service");

        encoder = new LogstashEncoder();
        encoder.setContext(context);
        encoder.setCustomFields("{\"service\":\"cart-service\"}");
        ShortenedThrowableConverter throwables = new ShortenedThrowableConverter();
        throwables.setMaxDepthPerThrowable(40);
        throwables.setRootCauseFirst(true);
        throwables.setContext(context);
        throwables.start();
        encoder.setThrowableConverter(throwables);
        encoder.start();
    }

    private JsonNode encode(LoggingEvent event) {
        return JSON.readTree(new String(encoder.encode(event), StandardCharsets.UTF_8));
    }

    private LoggingEvent event(Level level, String message, Throwable throwable) {
        Logger logger = context.getLogger("com.easyshop.example.Thing");
        LoggingEvent event = new LoggingEvent(
                "com.easyshop.example.Thing", logger, level, message, throwable, null);
        event.setThreadName("http-nio-8087-exec-1");
        // The encoder snapshots the MDC from the event, not from the thread at
        // encode time - which is exactly how it behaves in production, where
        // encoding happens on an appender thread.
        event.setMDCPropertyMap(MDC.getCopyOfContextMap() == null
                ? java.util.Map.of() : MDC.getCopyOfContextMap());
        return event;
    }

    @Test
    void aLogLineIsValidJsonWithTheExpectedFields() {
        JsonNode json = encode(event(Level.INFO, "cart merged", null));

        assertThat(json.has("@timestamp")).isTrue();
        assertThat(json.get("level").asString()).isEqualTo("INFO");
        assertThat(json.get("message").asString()).isEqualTo("cart merged");
        assertThat(json.get("logger_name").asString()).isEqualTo("com.easyshop.example.Thing");
        assertThat(json.get("thread_name").asString()).isEqualTo("http-nio-8087-exec-1");
    }

    /**
     * The field that ties logs to the rest of the observability stack. Without
     * a service field, a query has to infer origin from the container name,
     * which changes with replica count and orchestrator.
     */
    @Test
    void everyLineCarriesTheServiceName() {
        JsonNode json = encode(event(Level.INFO, "anything", null));

        assertThat(json.get("service").asString()).isEqualTo("cart-service");
    }

    /**
     * THE reason for structured logging here: traceId as a real field, so a log
     * line found in Grafana links directly to its Jaeger trace. Buried in the
     * message text it would need a regex per query and would break the moment
     * the message wording changed.
     */
    @Test
    void traceContextIsEmittedAsFieldsNotBuriedInTheMessage() {
        MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put("spanId", "00f067aa0ba902b7");
        try {
            JsonNode json = encode(event(Level.INFO, "charging payment", null));

            assertThat(json.get("traceId").asString()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
            assertThat(json.get("spanId").asString()).isEqualTo("00f067aa0ba902b7");
        } finally {
            MDC.clear();
        }
    }

    /**
     * A multi-line exception must arrive as ONE field on ONE event. Left as raw
     * console output it is ingested as N unrelated entries, and the single line
     * naming the real cause ends up orphaned from the request that produced it -
     * which is precisely the line you are looking for during an incident.
     */
    @Test
    void stackTracesAreASingleFieldOnOneEvent() {
        Throwable cause = new IllegalStateException("redis unreachable");
        Throwable wrapper = new RuntimeException("could not load cart", cause);

        String encoded = new String(encoder.encode(event(Level.ERROR, "cart load failed", wrapper)),
                StandardCharsets.UTF_8);
        JsonNode json = JSON.readTree(encoded);

        assertThat(json.get("stack_trace").asString())
                .contains("redis unreachable")
                .contains("could not load cart");
        // One event, one line: the encoder appends exactly one newline, so a
        // shipper reading line-by-line gets one record.
        assertThat(encoded.trim()).doesNotContain("\n");
    }

    @Test
    void arbitraryMdcValuesBecomeQueryableFields() {
        MDC.put("orderId", "9cf20032-c716-47d7-9e10-1a01c56d9863");
        try {
            JsonNode json = encode(event(Level.INFO, "order confirmed", null));

            // MDC is the supported way to attach request-scoped context; this
            // asserts it survives into the JSON so services can add their own
            // without further encoder configuration.
            assertThat(json.get("orderId").asString())
                    .isEqualTo("9cf20032-c716-47d7-9e10-1a01c56d9863");
        } finally {
            MDC.clear();
        }
    }
}
