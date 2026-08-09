package com.easyshop.order.outbox;

import com.easyshop.common.outbox.TraceContextCodec;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import com.easyshop.order.repository.OutboxRepository;
import jakarta.transaction.Transactional;
import lombok.extern.log4j.Log4j2;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Log4j2
@Component
public class OutboxPublisher {

    private final OutboxRepository outboxRepository;

    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxPublisher(OutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${outbox.publisher.fixed-delay-ms:500}")
    @Transactional
    public void publishPendingEvents() {

        List<OutboxEvent> batch = outboxRepository.findUnpublishedBatchForUpdate();

        if (batch.isEmpty()) {
            log.info("No pending events to publish.");
            return;
        }

        batch.forEach(event -> {
            try {
                // Sent inside the trace context captured when the row was
                // written, so the producer span - and everything the consumer
                // does downstream - is a child of the ORIGINATING request
                // rather than of this polling task. See AbstractOutboxPublisher
                // in common-lib for the same logic; this service has its own
                // copy because its outbox classes predate that shared base.
                sendWithinOriginatingTrace(event);
                event.markPublished();
                log.info("Publishing event {} of type {} for aggregate {} to topic {}",
                        event.getId(), event.getEventType(), event.getAggregateId(), event.getTopic());
            } catch (Exception e) {
                log.error("Failed to publish event {}: {}", event.getId(), e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Makes the stored trace context current for the duration of the send.
     *
     * The scope is what matters, not the headers: Spring Kafka's producer
     * observation reads the CURRENT context to choose its parent and to inject
     * the outgoing traceparent, so restoring the context is enough to graft the
     * whole downstream chain back onto the original trace. With no stored
     * context the send proceeds normally and roots its own trace - correct for
     * rows written before this existed, and far better than dropping an event.
     */
    private void sendWithinOriginatingTrace(OutboxEvent event) throws Exception {
        Context originating = TraceContextCodec.restore(event.getTraceParent());
        if (originating == null) {
            kafkaTemplate.send(event.getTopic(), event.getAggregateId().toString(), event.getPayload()).get();
            return;
        }
        try (Scope ignored = originating.makeCurrent()) {
            kafkaTemplate.send(event.getTopic(), event.getAggregateId().toString(), event.getPayload()).get();
        }
    }
}
