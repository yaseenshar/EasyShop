package com.easyshop.order.outbox;

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
                kafkaTemplate.send(event.getTopic(), event.getAggregateId().toString(), event.getPayload()).get();
                event.markPublished();
                log.info("Publishing event {} of type {} for aggregate {} to topic {}",
                        event.getId(), event.getEventType(), event.getAggregateId(), event.getTopic());
            } catch (Exception e) {
                log.error("Failed to publish event {}: {}", event.getId(), e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }
}
