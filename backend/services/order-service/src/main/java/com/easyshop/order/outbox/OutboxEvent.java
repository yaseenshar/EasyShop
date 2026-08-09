package com.easyshop.order.outbox;

import com.easyshop.common.outbox.TraceContextCodec;
import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Getter
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(nullable = false, length = 100)
    private String topic;

    // JSONB column - stores the serialized event payload as a JSON string.
    // Using @JdbcTypeCode(SqlTypes.JSON) is the current Hibernate 7.x idiom
    // (Hibernate 7 ships as part of the verified Spring Boot 4.1 dependency
    // management) for mapping a String/JsonNode field to a native jsonb column.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(nullable = false)
    private boolean published = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {}

    public static OutboxEvent of(String aggregateType, UUID aggregateId,
                                 String eventType, String topic, String payloadJson) {
        OutboxEvent event = new OutboxEvent();
        event.aggregateType = aggregateType;
        event.aggregateId = aggregateId;
        event.eventType = eventType;
        event.topic = topic;
        event.payload = payloadJson;
        return event;
    }

    /**
     * W3C traceparent of the request that produced this event - see
     * TraceContextCodec and common-lib's BaseOutboxEvent for the reasoning.
     *
     * NOTE this class does NOT extend BaseOutboxEvent, unlike payment-service's
     * and inventory-service's equivalents, so it carries its own copy of this
     * field and its own capture in @PrePersist. That divergence is exactly why
     * the first attempt at this change worked in payment and inventory and
     * silently did nothing here. Folding this entity onto the shared base would
     * remove the trap and is worth its own ticket.
     */
    @Column(name = "trace_parent", length = 55)
    private String traceParent;

    public String getTraceParent() {
        return traceParent;
    }

    public void markPublished() {
        this.published = true;
        this.publishedAt = Instant.now();
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        this.traceParent = TraceContextCodec.currentTraceParent();
    }
}
