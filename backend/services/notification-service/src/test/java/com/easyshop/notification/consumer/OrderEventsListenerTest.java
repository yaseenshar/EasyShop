package com.easyshop.notification.consumer;

import com.easyshop.common.event.OrderCreatedEvent;
import com.easyshop.common.event.OrderEvents.OrderCancelledEvent;
import com.easyshop.common.event.OrderEvents.OrderCompletedEvent;
import com.easyshop.notification.channel.NotificationChannel.NotificationType;
import com.easyshop.notification.channel.NotificationDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Covers the structural discriminator in OrderEventsListener#onOrderEvent -
 * order.events carries three record shapes now (OrderCreatedEvent was added
 * alongside Completed/Cancelled), and the discriminator order matters:
 * OrderCreatedEvent shares "totalAmount" with OrderCompletedEvent, so the
 * "items" check has to run first or created orders get misread as
 * completed ones and trigger a premature "order confirmed" notification.
 */
class OrderEventsListenerTest {

    // Mirrors the production ObjectMapper bean (JavaTimeModule registered via
    // Spring Boot's Jackson autoconfiguration) - without it, serializing the
    // Instant occurredAt field throws InvalidDefinitionException.
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private NotificationDispatcher dispatcher;
    private OrderEventsListener listener;
    private Acknowledgment ack;

    @BeforeEach
    void setUp() {
        dispatcher = mock(NotificationDispatcher.class);
        listener = new OrderEventsListener(dispatcher, objectMapper);
        ack = mock(Acknowledgment.class);
    }

    @Test
    void cancelledEvent_dispatchesOrderCancelledAndAcks() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var event = new OrderCancelledEvent(orderId, userId, "payment declined", Instant.now());

        listener.onOrderEvent(objectMapper.writeValueAsString(event), ack);

        verify(dispatcher).dispatch(eq(orderId), eq(userId), eq(NotificationType.ORDER_CANCELLED),
                anyString(), anyString());
        verify(ack).acknowledge();
    }

    @Test
    void completedEvent_dispatchesOrderConfirmedAndAcks() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var event = new OrderCompletedEvent(orderId, userId, new BigDecimal("49.99"), Instant.now());

        listener.onOrderEvent(objectMapper.writeValueAsString(event), ack);

        verify(dispatcher).dispatch(eq(orderId), eq(userId), eq(NotificationType.ORDER_CONFIRMED),
                anyString(), anyString());
        verify(ack).acknowledge();
    }

    @Test
    void createdEvent_isDroppedNotMisreadAsCompleted() throws Exception {
        // The regression this test guards against: OrderCreatedEvent also
        // carries "totalAmount", so without the "items" check running first
        // it would fall into the OrderCompletedEvent branch below and fire
        // an "order confirmed" notification before payment/stock ever ran.
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var items = List.of(new OrderCreatedEvent.OrderItem(UUID.randomUUID(), 2, new BigDecimal("19.99")));
        var event = new OrderCreatedEvent(UUID.randomUUID(), orderId, userId, items,
                new BigDecimal("39.98"), UUID.randomUUID(), Instant.now());

        listener.onOrderEvent(objectMapper.writeValueAsString(event), ack);

        verifyNoInteractions(dispatcher);
        verify(ack).acknowledge();
    }

    @Test
    void unknownShape_isDroppedButStillAcked() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of("unexpectedField", "value"));

        listener.onOrderEvent(payload, ack);

        verifyNoInteractions(dispatcher);
        verify(ack).acknowledge();
    }

    @Test
    void malformedJson_throwsAndDoesNotAck() {
        assertThatThrownBy(() -> listener.onOrderEvent("not-json", ack))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(dispatcher);
        verify(ack, never()).acknowledge();
    }
}
