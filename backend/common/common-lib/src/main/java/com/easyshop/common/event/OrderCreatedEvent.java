package com.easyshop.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * OrderCreatedEvent — published by order-service, consumed by payment-service
 * and inventory-service as part of the Saga pattern.
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
        String shippingAddress,
        Instant occurredAt     // When the event happened, not when it was published
) {
    public record OrderItem(UUID productId, int quantity, BigDecimal unitPrice) {}
}
