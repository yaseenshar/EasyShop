package com.easyshop.order.saga;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class OrderEvents {

    public record OrderCompletedEvent(UUID orderId, UUID userId, BigDecimal totalAmount, Instant occurredAt) {
    }

    public record OrderCancelledEvent(UUID orderId, UUID userId, String reason, Instant occurredAt) {
    }
}
