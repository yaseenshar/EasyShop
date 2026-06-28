package com.easyshop.common.outbox;

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
                kafkaTemplate.send(event.getTopic(), event.getAggregateId().toString(),
                        event.getPayload()).get();
                event.markPublished();
                log.info("Published outbox event {} of type {} to topic {}",
                        event.getId(), event.getEventType(), event.getTopic());
            } catch (Exception e) {
                log.error("Failed to publish outbox event {}, will retry next cycle",
                        event.getId(), e);
            }
        }
    }
}