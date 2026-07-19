package com.easyshop.inventory.outbox;

import com.easyshop.common.outbox.AbstractOutboxPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublisher extends AbstractOutboxPublisher<OutboxEvent> {

    public OutboxPublisher(OutboxRepository repository, KafkaTemplate<String, String> kafkaTemplate) {
        super(repository, kafkaTemplate);
    }
}
