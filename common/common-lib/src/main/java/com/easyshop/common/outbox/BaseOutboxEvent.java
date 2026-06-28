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
    @Column(nullable = false, columnDefinition = "jsonb")
    protected String payload;

    @Column(nullable = false)
    protected boolean published = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    protected Instant createdAt;

    @Column(name = "published_at")
    protected Instant publishedAt;

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
    }
}