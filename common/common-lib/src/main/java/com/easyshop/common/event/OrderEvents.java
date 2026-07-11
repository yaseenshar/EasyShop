package com.easyshop.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * MOVED from order-service's saga package to common-lib (delete
 * com.easyshop.order.saga.OrderEvents and update order-service's imports
 * to this class). Same reasoning as the earlier SagaMessages move: the
 * moment a second service (notification-service) needs to deserialize
 * these records, keeping the definition inside the producer creates
 * contract-drift risk between producer and consumer.
 *
 * These are choreography fan-out events on topic "order.events" -
 * order-service publishes them at saga terminal states and has no
 * knowledge of who consumes them.
 */
public final class OrderEvents {

    private OrderEvents() {}

    public record OrderCompletedEvent(
            UUID orderId,
            UUID userId,
            BigDecimal totalAmount,
            Instant occurredAt
    ) {}

    public record OrderCancelledEvent(
            UUID orderId,
            UUID userId,
            String reason,
            Instant occurredAt
    ) {}
}