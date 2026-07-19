package com.easyshop.inventory.outbox;

import com.easyshop.common.outbox.BaseOutboxEvent;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent extends BaseOutboxEvent {

    protected OutboxEvent() {}

    public static OutboxEvent of(String aggregateType, UUID aggregateId,
                                 String eventType, String topic, String payloadJson) {
        OutboxEvent event = new OutboxEvent();
        event.initialize(aggregateType, aggregateId, eventType, topic, payloadJson);
        return event;
    }
}