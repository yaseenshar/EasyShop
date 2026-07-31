package com.easyshop.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * OrderCreatedEvent — a choreography fan-out event on topic "order.events"
 * (see SagaTopics.ORDER_EVENTS), published by order-service the moment an
 * order is persisted and the saga starts. Unlike ReserveStockCommand/
 * ChargePaymentCommand (which drive the orchestrated saga directly),
 * order-service has no knowledge of who consumes this - it's for
 * non-saga-critical subscribers (analytics, audit, notification-service).
 *
 * Key design: events are immutable value objects. Never send mutable objects
 * over Kafka — they are serialized at publish time and any changes after
 * publish are lost. Records enforce this at the language level.
 */
public record OrderCreatedEvent(
        UUID eventId,          // Unique event ID — for idempotent consumers
        UUID orderId,
        UUID userId,
        List<OrderItem> items,
        BigDecimal totalAmount,
        UUID shippingAddressId,
        Instant occurredAt     // When the event happened, not when it was published
) {
    public record OrderItem(UUID productId, int quantity, BigDecimal unitPrice) {}
}
