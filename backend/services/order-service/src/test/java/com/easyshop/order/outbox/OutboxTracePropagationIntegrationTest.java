package com.easyshop.order.outbox;

import com.easyshop.order.config.KafkaConfig;
import com.easyshop.order.repository.OutboxRepository;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Asserts the traceparent header that actually lands on the Kafka record.
 *
 * WHY AT THE WIRE AND NOT IN JAEGER. A trace tree in a UI is a pleasant thing
 * to look at once; it is not a regression test, and this exact propagation has
 * broken silently three times in this codebase - once when a Boot 4 property
 * was renamed, once because these services build their own KafkaTemplate and so
 * bypassed spring.kafka.template.observation-enabled entirely, and once because
 * the transactional outbox severed the thread-local trace scope. Every one of
 * those looked completely healthy: the saga ran, the order reached CONFIRMED,
 * and no header was emitted. Only the bytes on the record distinguish "working"
 * from "quietly not".
 *
 * The context is deliberately RESTORED from a stored string rather than being
 * ambient, because that is the outbox's real situation: the request that caused
 * the event finished long ago on another thread.
 */
@SpringBootTest(
        classes = OutboxTracePropagationIntegrationTest.KafkaTracingTestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                // Tracing must be ON and sampling everything, or the producer
                // instrumentation has no span to propagate.
                "management.tracing.sampling.probability=1.0",
                // Nothing should try to reach a collector during a unit test;
                // the assertion is on the Kafka header, not on export.
                "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:1/v1/traces",
                "management.otlp.metrics.export.enabled=false"
        })
@Testcontainers
class OutboxTracePropagationIntegrationTest {

    private static final String TOPIC = "trace-propagation-test";
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";

    @Container
    static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockitoBean
    private OutboxRepository outboxRepository;

    private OutboxPublisher publisher;
    private KafkaConsumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        publisher = new OutboxPublisher(outboxRepository, kafkaTemplate);

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "trace-test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(TOPIC));
        consumer.poll(Duration.ofMillis(500)); // force assignment before producing
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.close();
        }
    }

    /**
     * The headline guarantee: an event whose row carries a traceparent is
     * published on a record carrying THAT SAME trace id, not a fresh one.
     */
    @Test
    void thePublishedRecordCarriesTheOriginatingTraceId() {
        OutboxEvent event = eventWithStoredTrace(traceParent("01"));
        when(outboxRepository.findUnpublishedBatchForUpdate()).thenReturn(List.of(event));

        publisher.publishPendingEvents();

        String traceParent = headerOf(pollOne(), "traceparent");
        assertThat(traceParent)
                .as("Spring Kafka must inject a traceparent header")
                .isNotNull();
        assertThat(traceParent)
                .as("and it must continue the ORIGINATING trace, not the publisher's own")
                .contains(TRACE_ID);
    }

    /**
     * The W3C shape matters as much as the id: a consumer parses this string,
     * and a malformed one is silently ignored, which looks exactly like no
     * propagation at all.
     */
    @Test
    void theHeaderIsAWellFormedW3CTraceparent() {
        OutboxEvent event = eventWithStoredTrace(traceParent("01"));
        when(outboxRepository.findUnpublishedBatchForUpdate()).thenReturn(List.of(event));

        publisher.publishPendingEvents();

        String traceParent = headerOf(pollOne(), "traceparent");
        assertThat(traceParent).hasSize(55);
        assertThat(traceParent).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
        // The span id must be the PUBLISHER's new span, not a copy of the
        // stored parent - a record that claims its parent's span id produces a
        // cycle that trace UIs render as a broken tree.
        assertThat(traceParent).doesNotContain(SPAN_ID);
    }

    /**
     * A row written before the trace_parent column existed, or by an untraced
     * path, must still publish. Losing a trace link is acceptable; refusing to
     * emit the event because it has no trace context would turn an
     * observability feature into an outage.
     */
    @Test
    void anEventWithNoStoredTraceStillPublishes() {
        OutboxEvent event = eventWithStoredTrace(null);
        when(outboxRepository.findUnpublishedBatchForUpdate()).thenReturn(List.of(event));

        publisher.publishPendingEvents();

        ConsumerRecord<String, String> record = pollOne();
        assertThat(record).isNotNull();
        assertThat(record.value()).isEqualTo("{\"hello\":\"world\"}");
        assertThat(event.isPublished()).isTrue();
    }

    /** A stored value that cannot be parsed is treated as absent, never fatal. */
    @Test
    void aMalformedStoredTraceDoesNotBreakPublishing() {
        OutboxEvent event = eventWithStoredTrace("not-a-valid-traceparent");
        when(outboxRepository.findUnpublishedBatchForUpdate()).thenReturn(List.of(event));

        publisher.publishPendingEvents();

        assertThat(pollOne()).isNotNull();
        assertThat(event.isPublished()).isTrue();
    }

    // ---------------------------------------------------------------- helpers

    private static String traceParent(String flags) {
        return "00-" + TRACE_ID + "-" + SPAN_ID + "-" + flags;
    }

    /**
     * Builds an event and stamps the stored traceparent the way @PrePersist
     * would have, by writing inside a restored scope - so the field is set by
     * the production code path rather than by the test reaching into it.
     */
    private OutboxEvent eventWithStoredTrace(String storedTraceParent) {
        if (storedTraceParent == null) {
            return newEvent();
        }
        // A malformed value cannot be produced by a real scope - no valid span
        // serialises to garbage - so it is written directly. Length is checked
        // FIRST: parsing flags out of a short string is what this test caught
        // itself doing.
        if (storedTraceParent.length() != 55) {
            OutboxEvent malformed = newEvent();
            setTraceParent(malformed, storedTraceParent);
            return malformed;
        }
        SpanContext sc = SpanContext.create(TRACE_ID, SPAN_ID,
                TraceFlags.fromHex(storedTraceParent.substring(53), 0), TraceState.getDefault());
        try (Scope ignored = Context.root().with(Span.wrap(sc)).makeCurrent()) {
            OutboxEvent event = newEvent();
            event.onCreate();
            return event;
        }
    }

    private static OutboxEvent newEvent() {
        return OutboxEvent.of("Order", UUID.randomUUID(), "TestEvent", TOPIC, "{\"hello\":\"world\"}");
    }

    /** Only used for the deliberately-malformed case, which no scope can produce. */
    private static void setTraceParent(OutboxEvent event, String value) {
        try {
            var field = OutboxEvent.class.getDeclaredField("traceParent");
            field.setAccessible(true);
            field.set(event, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private ConsumerRecord<String, String> pollOne() {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                return record;
            }
        }
        return null;
    }

    private static String headerOf(ConsumerRecord<String, String> record, String name) {
        if (record == null) {
            return null;
        }
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /**
     * Imports the REAL KafkaConfig, because the bug this guards against was in
     * that class: observation has to be switched on at the bean, and a test
     * that built its own template would prove nothing about production.
     */
    @Configuration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class
    })
    @Import(KafkaConfig.class)
    static class KafkaTracingTestApp {
    }
}
