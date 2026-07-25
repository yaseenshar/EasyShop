package com.easyshop.payment.saga;

import java.time.Duration;

import com.easyshop.common.idempotency.IdempotencyRecord;
import com.easyshop.common.idempotency.IdempotencyStore;
import com.easyshop.common.saga.SagaIdempotencyKeys;
import com.easyshop.common.saga.SagaMessages.ChargePaymentCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * OUTER bean: owns the idempotency DECISION (Redis lock, DB-race backstop,
 * ack/no-ack), owns NO transaction itself - see PaymentChargeAndRefundTxn's
 * javadoc for why the actual charge work has to live in a different bean.
 *
 * Consumes ChargePaymentCommand (published by order-service's saga
 * orchestrator) and produces PaymentReply via the local outbox.
 *
 * THREE-LAYER DEFENSE for the one irreversible operation in the system:
 *   1. Redis IdempotencyStore lock/result — fast dedupe of Kafka redelivery
 *   2. payment DB UNIQUE(idempotency_key), now keyed on commandId — backstop
 *   3. the PSP's OWN idempotency key (SagaIdempotencyKeys.pspKey) — dedupes
 *      even if we crash after the PSP charges but before we record it
 *
 * KEY IS DETERMINISTIC, not random: commandId is stamped once by the
 * orchestrator (see OrderSagaOrchestrator#handleStockReservationReply) and
 * travels inside the outbox-sourced message, so every Kafka redelivery of
 * THIS message computes the same key. A key derived from anything regenerated
 * per-delivery would defeat the whole mechanism.
 */
@Component
public class PaymentSagaListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentSagaListener.class);
    private static final Duration IN_PROGRESS_TTL = Duration.ofSeconds(60);
    private static final Duration COMPLETED_TTL = Duration.ofHours(24);

    private final IdempotencyStore idempotencyStore;
    private final PaymentChargeAndRefundTxn txn;
    private final ObjectMapper objectMapper;

    public PaymentSagaListener(IdempotencyStore idempotencyStore, PaymentChargeAndRefundTxn txn,
                               ObjectMapper objectMapper) {
        this.idempotencyStore = idempotencyStore;
        this.txn = txn;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "payment.charge.command", groupId = "payment-service")
    public void onChargeCommand(ChargePaymentCommand command, Acknowledgment ack) {
        if (handleChargeCommand(command)) {
            ack.acknowledge();
        }
        // else: another delivery is mid-flight for this exact commandId - do
        // NOT ack, so Kafka redelivers once it's clear (the in-progress TTL
        // self-heals if that other attempt died mid-way).
    }

    private boolean handleChargeCommand(ChargePaymentCommand command) {
        String commandId = command.commandId().toString();
        String key = SagaIdempotencyKeys.charge(commandId);
        String fingerprint = PaymentFingerprint.charge(command.orderId(), command.amount(), command.currency());

        IdempotencyStore.Begin begin;
        try {
            begin = idempotencyStore.begin(key, fingerprint, IN_PROGRESS_TTL);
        } catch (DataAccessException redisDown) {
            // Fail OPEN (§4.8): process anyway - the DB UNIQUE constraint is
            // the backstop below.
            log.warn("Idempotency store unreachable for charge {} - processing without the Redis fast path",
                    commandId, redisDown);
            return chargeAndComplete(command, commandId, key, fingerprint);
        }

        if (begin instanceof IdempotencyStore.Begin.Completed) {
            // The outbox already emitted PaymentReply exactly once for this
            // commandId (§4.7) - the saga already advanced. Redelivery is a
            // pure no-op: no re-charge, no re-publish.
            log.info("Charge command {} already completed - redelivery is a no-op", commandId);
            return true;
        }
        if (begin instanceof IdempotencyStore.Begin.InProgress) {
            log.warn("Charge command {} is in-flight on another delivery - not acking, Kafka will redeliver", commandId);
            return false;
        }

        // Acquired - we own this charge.
        return chargeAndComplete(command, commandId, key, fingerprint);
    }

    private boolean chargeAndComplete(ChargePaymentCommand command, String commandId, String key, String fingerprint) {
        try {
            var reply = txn.charge(command, commandId);
            // Cache AFTER the DB transaction has committed, not before - if we
            // marked Redis complete first and the commit then failed for some
            // external reason, Redis would say "done" for a charge that never
            // durably happened. This ordering is what makes Redis lag the DB
            // by at most a few milliseconds instead of possibly lying.
            idempotencyStore.complete(key,
                    IdempotencyRecord.of(200, "application/json", serializeQuietly(reply).getBytes(), fingerprint),
                    COMPLETED_TTL);
            return true;
        } catch (DataIntegrityViolationException dup) {
            // Layer 3: the DB UNIQUE constraint caught a race the Redis lock
            // missed (e.g. Redis briefly failed open). The charge already
            // exists under this commandId - release the now-stale lock and
            // treat this delivery as done, not failed.
            idempotencyStore.release(key);
            log.info("Charge command {} raced the DB unique constraint - already recorded", commandId);
            return true;
        } catch (RuntimeException transientFailure) {
            idempotencyStore.release(key);
            log.warn("Transient failure processing charge {} - releasing lock, not acking, Kafka will redeliver",
                    commandId, transientFailure);
            return false;
        }
    }

    private String serializeQuietly(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize payment reply", e);
        }
    }
}
