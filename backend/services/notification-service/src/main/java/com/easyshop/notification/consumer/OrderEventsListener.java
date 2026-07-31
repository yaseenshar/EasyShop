package com.easyshop.notification.consumer;

import com.easyshop.common.event.OrderEvents.OrderCancelledEvent;
import com.easyshop.common.event.OrderEvents.OrderCompletedEvent;
import com.easyshop.notification.channel.NotificationChannel.NotificationType;
import com.easyshop.notification.channel.NotificationDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Consumes the order.events choreography topic.
 *
 * A REAL PROBLEM this class has to solve that single-event-type listeners
 * don't: order.events carries TWO different record shapes
 * (OrderCompletedEvent and OrderCancelledEvent) on one topic. The
 * JsonMessageConverter can't pick a target type from the listener
 * parameter when the parameter would have to be two different things -
 * so this listener takes the raw JSON String and discriminates on
 * structure. The cleaner long-term fix is type-id headers (the outbox
 * publisher would set a "__TypeId__" header from the eventType column it
 * already stores, and Spring Kafka's converter resolves the type from it) -
 * flagged as the upgrade path; the structural discrimination below keeps
 * the outbox publisher untouched for now.
 */
@Log4j2
@Component
public class OrderEventsListener {


    private final NotificationDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    public OrderEventsListener(NotificationDispatcher dispatcher, ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "order.events", groupId = "notification-service")
    public void onOrderEvent(String payload, Acknowledgment ack) {
        try {
            JsonNode node = objectMapper.readTree(payload);

            // Structural discrimination: OrderCancelledEvent carries a
            // "reason" field; OrderCompletedEvent carries "totalAmount".
            // OrderCreatedEvent ALSO carries "totalAmount" (plus "items",
            // which Completed/Cancelled don't have) - it must be excluded
            // explicitly here or it gets misread as a completed order.
            if (node.has("reason")) {
                handleCancelled(objectMapper.treeToValue(node, OrderCancelledEvent.class));
            } else if (node.has("items")) {
                // OrderCreatedEvent - not notification-worthy today, drop.
                log.debug("Ignoring OrderCreatedEvent on order.events (no notification defined for it yet)");
            } else if (node.has("totalAmount")) {
                handleCompleted(objectMapper.treeToValue(node, OrderCompletedEvent.class));
            } else {
                // Unknown shape - log and drop rather than DLT-ing forever:
                // a brand-new event type added by order-service should not
                // poison this consumer (tolerant reader principle).
                log.warn("Unknown event shape on order.events, ignoring: {}", payload);
            }

            ack.acknowledge();
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            // Malformed JSON - not retryable (configured in KafkaConfig),
            // goes straight to order.events.DLT.
            throw new IllegalArgumentException("Unparseable order event", e);
        }
    }

    private void handleCompleted(OrderCompletedEvent event) {
        dispatcher.dispatch(
                event.orderId(), event.userId(), NotificationType.ORDER_CONFIRMED,
                "Your EasyShop order is confirmed!",
                "Order %s for %s %s has been confirmed and is being prepared."
                        .formatted(shortId(event.orderId()), event.totalAmount(), "USD"));
    }

    private void handleCancelled(OrderCancelledEvent event) {
        dispatcher.dispatch(
                event.orderId(), event.userId(), NotificationType.ORDER_CANCELLED,
                "Your EasyShop order was cancelled",
                "Order %s could not be completed: %s. You have not been charged."
                        .formatted(shortId(event.orderId()), event.reason()));
    }

    private String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}