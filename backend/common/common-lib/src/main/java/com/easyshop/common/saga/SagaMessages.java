package com.easyshop.common.saga;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Shared saga message contracts, living in common-lib so that EVERY
 * participant (order-service, payment-service, inventory-service) compiles
 * against the exact same record definitions. This was originally drafted
 * separately inside order-service (Phase 3's SagaMessages) and inside
 * payment-service - duplicating a wire contract across services is a real
 * design smell: it lets the two sides silently drift out of sync (e.g. one
 * side adds a field, the other doesn't, and you only find out at runtime
 * deserialization). Centralizing it here in common-lib is the fix, mirroring
 * the same reasoning that moved the Outbox base classes here.
 *
 * order-service's saga/SagaMessages.java and payment-service's saga payload
 * records should now be deleted in favor of importing directly from here -
 * noted as a follow-up cleanup task.
 */
public final class SagaMessages {

    private SagaMessages() {}

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
            String idempotencyKey,
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