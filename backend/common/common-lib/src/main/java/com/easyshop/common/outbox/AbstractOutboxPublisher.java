package com.easyshop.common.outbox;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Generic outbox polling publisher. Each service wires up a concrete
 * @Component subclass (or a @Bean factory method) supplying its own
 * BaseOutboxRepository<T> implementation. See payment-service's
 * OutboxPublisherConfig for the minimal wiring needed.
 */
public abstract class AbstractOutboxPublisher<T extends BaseOutboxEvent> {

    private static final Logger log = LoggerFactory.getLogger(AbstractOutboxPublisher.class);

    private final BaseOutboxRepository<T> repository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    protected AbstractOutboxPublisher(BaseOutboxRepository<T> repository,
                                      KafkaTemplate<String, String> kafkaTemplate) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void publishPendingEvents() {
        List<T> batch = repository.findUnpublishedBatchForUpdate();
        if (batch.isEmpty()) {
            return;
        }

        for (T event : batch) {
            try {
                publishWithinOriginatingTrace(event);
                event.markPublished();
                log.info("Published outbox event {} of type {} to topic {}",
                        event.getId(), event.getEventType(), event.getTopic());
            } catch (Exception e) {
                log.error("Failed to publish outbox event {}, will retry next cycle",
                        event.getId(), e);
            }
        }
    }

    /**
     * Sends the record inside the trace context captured when the row was
     * written, so the Kafka producer span becomes a child of the ORIGINATING
     * request rather than of this polling task.
     *
     * The scope is what does the work, not any header manipulation: Spring
     * Kafka's producer observation reads the CURRENT context to decide its
     * parent and to inject the outgoing traceparent. Making the restored
     * context current for the duration of send() is therefore enough to graft
     * the whole downstream chain - producer span, consumer span, and everything
     * the consumer does - back onto the original trace. Writing the traceparent
     * header by hand instead would be overwritten by that same instrumentation.
     *
     * WHEN THERE IS NO STORED CONTEXT the send happens normally and simply
     * roots its own trace. That is the correct outcome for a row written before
     * this column existed, or by an untraced path - a slightly less connected
     * trace is much better than dropping the event.
     */
    private void publishWithinOriginatingTrace(T event) throws Exception {
        Context originating = TraceContextCodec.restore(event.getTraceParent());
        if (originating == null) {
            kafkaTemplate.send(event.getTopic(), event.getAggregateId().toString(),
                    event.getPayload()).get();
            return;
        }
        try (Scope ignored = originating.makeCurrent()) {
            kafkaTemplate.send(event.getTopic(), event.getAggregateId().toString(),
                    event.getPayload()).get();
        }
    }
}