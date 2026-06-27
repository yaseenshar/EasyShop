package com.easyshop.order.saga;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Command and reply payloads for the order saga. Records (Java 25) give us
 * immutability and structural equality for free - exactly what you want for
 * message payloads that cross a network boundary and should never be mutated
 * after construction.
 */
public final class SagaMessages {

    private SagaMessages() {}

    // ── Commands: order-service -> participant ──────────────────────────

    public record ReserveStockCommand(
            UUID orderId,
            List<LineItem> items,
            Instant issuedAt
    ) {
        public record LineItem(UUID productId, int quantity) {}
    }

    public record ChargePaymentCommand(
            UUID orderId,
            UUID userId,
            BigDecimal amount,
            String currency,
            String idempotencyKey,   // reused from the order's own idempotency key
            Instant issuedAt
    ) {}

    public record ConfirmStockCommand(
            UUID orderId,
            UUID reservationId,
            Instant issuedAt
    ) {}

    public record ReleaseStockCommand(
            UUID orderId,
            UUID reservationId,
            Instant issuedAt
    ) {}

    // ── Replies: participant -> order-service ────────────────────────────

    public record StockReservationReply(
            UUID orderId,
            boolean success,
            UUID reservationId,
            String failureReason
    ) {}

    public record PaymentReply(
            UUID orderId,
            boolean success,
            UUID transactionId,
            String failureReason
    ) {}

    public record StockConfirmationReply(
            UUID orderId,
            boolean success,
            String failureReason
    ) {}
}