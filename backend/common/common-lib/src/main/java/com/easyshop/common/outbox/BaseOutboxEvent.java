package com.easyshop.common.outbox;

import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Shared Transactional Outbox entity, moved to common-lib because the shape
 * is identical across every service that needs to publish events atomically
 * with a local DB write (order-service, payment-service, inventory-service,
 * and any future participant). Each service still owns its OWN outbox_events
 * TABLE in its OWN database (polyglot persistence is preserved - this is
 * just shared Java mapping code, not a shared table).
 *
 * @MappedSuperclass (not @Entity) because each service's concrete
 * OutboxEvent subclass maps to that service's own table via @Entity
 * @Table on the subclass - see each service's outbox package for the
 * one-line subclass.
 */
@Getter
@MappedSuperclass
public abstract class BaseOutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    protected UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    protected String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    protected UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    protected String eventType;

    @Column(nullable = false, length = 100)
    protected String topic;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    protected String payload;

    @Column(nullable = false)
    protected boolean published = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    protected Instant createdAt;

    @Column(name = "published_at")
    protected Instant publishedAt;

    /**
     * The W3C traceparent of the request that CAUSED this event, carried so the
     * eventual publish can rejoin that trace.
     *
     * WHY THE OUTBOX NEEDS THIS AT ALL. Trace context lives in a thread-local
     * scope. The outbox deliberately severs the causal chain from the thread
     * that created the event - the request writes a row and returns, and a
     * scheduled publisher picks it up later on a different thread, often
     * seconds afterwards. Nothing survives that handoff except what is written
     * to the row. Measured before this column existed: 38 of 38 publisher spans
     * were trace ROOTS, so a checkout appeared in Jaeger as a chain of
     * disconnected traces rather than one story.
     *
     * Exactly 55 characters by spec: "00-" + 32 hex trace id + "-" + 16 hex
     * span id + "-" + 2 hex flags.
     */
    @Column(name = "trace_parent", length = 55)
    protected String traceParent;

    protected BaseOutboxEvent() {}

    protected void initialize(String aggregateType, UUID aggregateId, String eventType,
                              String topic, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.payload = payload;
    }

    public void markPublished() {
        this.published = true;
        this.publishedAt = Instant.now();
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        // Captured HERE rather than passed in by each caller: @PrePersist runs
        // on the thread that is writing the row, which is by definition still
        // inside the originating request's trace scope. Threading a traceparent
        // through every writeToOutbox() signature in three services would work
        // too, but it is a parameter that exists only to be forwarded and that
        // a new call site can silently forget.
        //
        // OpenTelemetry's Span.current() is a static API by design - it reads
        // the active context and returns an invalid span when there is none, so
        // this is safe on an untraced thread (a scheduled job, a test) and
        // simply stores null.
        this.traceParent = TraceContextCodec.currentTraceParent();
    }
}