package com.easyshop.payment.saga;

import java.time.Duration;

import com.easyshop.common.idempotency.IdempotencyRecord;
import com.easyshop.common.idempotency.IdempotencyStore;
import com.easyshop.common.saga.SagaIdempotencyKeys;
import com.easyshop.common.saga.SagaMessages.RefundPaymentCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * RefundPaymentCommand handler — the coverage gap this ticket closes. The
 * charge already had three-layer idempotency (PaymentSagaListener); the
 * reversal path did not, and a refund running twice is a direct financial
 * loss, not a display bug.
 *
 * Own key-space (SagaIdempotencyKeys.refund), never shared with the charge
 * key for the same order — a charge and a refund are different operations
 * and must dedupe independently (see SagaMessages.RefundPaymentCommand).
 *
 * WHEN THIS RUNS: there is no automatic trigger in the saga state machine —
 * per OrderSagaOrchestrator#handleStockConfirmationReply's javadoc, a
 * post-payment stock-confirm failure is deliberately flagged for human
 * review, not auto-refunded. This listener serves the operator-triggered
 * reversal (OrderController's admin refund endpoint /{orderId}/refund),
 * which must be safe against a double-click or a redelivered command — same
 * three layers as the charge: Redis lock, DB-level backstop (here:
 * optimistic locking via PaymentTransaction#version, not a UNIQUE constraint
 * — refund UPDATES an existing row rather than inserting one), and a
 * refund-specific PSP idempotency key.
 *
 * No reply topic: nothing consumes a refund-completed signal today.
 */
@Component
public class RefundSagaListener {

    private static final Logger log = LoggerFactory.getLogger(RefundSagaListener.class);
    private static final Duration IN_PROGRESS_TTL = Duration.ofSeconds(60);
    private static final Duration COMPLETED_TTL = Duration.ofHours(24);

    private final IdempotencyStore idempotencyStore;
    private final PaymentChargeAndRefundTxn txn;

    public RefundSagaListener(IdempotencyStore idempotencyStore, PaymentChargeAndRefundTxn txn) {
        this.idempotencyStore = idempotencyStore;
        this.txn = txn;
    }

    @KafkaListener(topics = "payment.refund.command", groupId = "payment-service")
    public void onRefundCommand(RefundPaymentCommand command, Acknowledgment ack) {
        if (handleRefundCommand(command)) {
            ack.acknowledge();
        }
    }

    private boolean handleRefundCommand(RefundPaymentCommand command) {
        String commandId = command.commandId().toString();
        String key = SagaIdempotencyKeys.refund(commandId);
        String fingerprint = PaymentFingerprint.refund(command.orderId(), command.originalTransactionId());

        IdempotencyStore.Begin begin;
        try {
            begin = idempotencyStore.begin(key, fingerprint, IN_PROGRESS_TTL);
        } catch (DataAccessException redisDown) {
            log.warn("Idempotency store unreachable for refund {} - processing without the Redis fast path",
                    commandId, redisDown);
            return refundAndComplete(command, commandId, key, fingerprint);
        }

        if (begin instanceof IdempotencyStore.Begin.Completed) {
            log.info("Refund command {} already completed - redelivery is a no-op", commandId);
            return true;
        }
        if (begin instanceof IdempotencyStore.Begin.InProgress) {
            log.warn("Refund command {} is in-flight on another delivery - not acking, Kafka will redeliver", commandId);
            return false;
        }

        return refundAndComplete(command, commandId, key, fingerprint);
    }

    private boolean refundAndComplete(RefundPaymentCommand command, String commandId, String key, String fingerprint) {
        try {
            txn.refund(command.orderId(), command.originalTransactionId(), commandId);
            idempotencyStore.complete(key,
                    IdempotencyRecord.of(200, "application/json", "{}".getBytes(), fingerprint),
                    COMPLETED_TTL);
            return true;
        } catch (ObjectOptimisticLockingFailureException racedUpdate) {
            // Layer 3: a concurrent second refund attempt won the @Version
            // race and already applied this update - this delivery is done,
            // not failed.
            idempotencyStore.release(key);
            log.info("Refund command {} lost the optimistic-lock race - already refunded by a concurrent attempt",
                    commandId);
            return true;
        } catch (IllegalStateException businessFailure) {
            // Wrong status (already refunded / never succeeded), or the
            // gateway declined the refund - NOT transient. Redelivering would
            // fail identically forever, so ack (do not loop) and log loudly:
            // this needs a human, same "flag for manual review" philosophy as
            // OrderSagaOrchestrator#handleStockConfirmationReply.
            idempotencyStore.release(key);
            log.error("Refund command {} failed permanently, will NOT be retried: {}",
                    commandId, businessFailure.getMessage());
            return true;
        } catch (RuntimeException transientFailure) {
            idempotencyStore.release(key);
            log.warn("Transient failure processing refund {} - releasing lock, not acking, Kafka will redeliver",
                    commandId, transientFailure);
            return false;
        }
    }
}
