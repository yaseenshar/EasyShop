package com.easyshop.common.saga; // align with common-lib layout (near SagaMessages)

/**
 * Deterministic idempotency keys for saga COMMANDS — the second key-space.
 *
 * Two key-spaces, kept separate on purpose (§4.8):
 *   1. ORDER-level (client): the Idempotency-Key HTTP header. Dedupes a browser
 *      retry -> one order, one saga. Lives on orders.idempotency_key (UNIQUE).
 *   2. COMMAND-level (this class): dedupes KAFKA REDELIVERY of a saga command
 *      -> the charge/refund runs once even though delivery is at-least-once.
 *
 * The command key MUST be stable across redeliveries, so it is NOT random. It is
 * derived from the saga command's own id — which is the Transactional Outbox
 * event id (§4.7). That row is written exactly once, so every Kafka redelivery
 * of that message carries the same id, so payment computes the same key every
 * time. A random per-message key would defeat the whole mechanism.
 *
 * The command id travels IN the command (a field on ChargePaymentCommand /
 * ReleasePaymentCommand in SagaMessages, §4.14) — set by the orchestrator when
 * it writes the outbox row. Payment never invents it.
 */
public final class SagaIdempotencyKeys {

    private SagaIdempotencyKeys() {}

    /** e.g. idem:payment:charge:{commandId} */
    public static String charge(String commandId) {
        return "idem:payment:charge:" + require(commandId);
    }

    /** e.g. idem:payment:refund:{commandId} — the reversal/void path is its OWN
     *  operation and its OWN key; a refund and a charge for the same order must
     *  never share a key. */
    public static String refund(String commandId) {
        return "idem:payment:refund:" + require(commandId);
    }

    /**
     * The value handed to the EXTERNAL PSP as ITS idempotency key. Deriving it
     * from the same commandId gives triple-layer protection for the irreversible
     * charge: our Redis lock + our DB unique constraint + the PSP's own dedupe.
     * Even if we crash after the PSP charges but before we record it, the
     * redelivery re-calls the PSP with the SAME key and the PSP returns the
     * original result instead of charging twice.
     */
    public static String pspKey(String commandId) {
        return "easyshop-charge-" + require(commandId);
    }

    /** The refund-specific PSP idempotency key - never the same value as pspKey()
     *  for the same commandId, since a charge and a refund are different PSP
     *  operations and must never accidentally dedupe against each other. */
    public static String refundPspKey(String commandId) {
        return "easyshop-refund-" + require(commandId);
    }

    private static String require(String commandId) {
        if (commandId == null || commandId.isBlank()) {
            // A missing command id means the orchestrator didn't stamp the outbox
            // event — a wiring bug that would silently disable redelivery dedupe.
            // Fail loud rather than derive a useless key.
            throw new IllegalStateException("saga command id is required for idempotency");
        }
        return commandId;
    }
}