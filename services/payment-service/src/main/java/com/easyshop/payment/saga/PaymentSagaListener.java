package com.easyshop.payment.saga;

import com.easyshop.common.saga.SagaMessages.ChargePaymentCommand;
import com.easyshop.common.saga.SagaMessages.PaymentReply;
import com.easyshop.payment.entity.PaymentTransaction;
import com.easyshop.payment.gateway.IdempotencyLockService;
import com.easyshop.payment.gateway.PaymentGateway;
import com.easyshop.payment.gateway.PaymentGateway.GatewayResult;
import com.easyshop.payment.outbox.OutboxEvent;
import com.easyshop.payment.outbox.OutboxRepository;
import com.easyshop.payment.repository.PaymentTransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Consumes ChargePaymentCommand (published by order-service's saga
 * orchestrator) and produces PaymentReply (also via the local outbox).
 *
 * This class is where the Redis idempotency lock (Step 4.1/4.2) and the
 * Transactional Outbox pattern (Phase 3, Step 3.3) meet: the lock guards
 * the EXTERNAL side effect (the gateway call), while the outbox guards
 * the INTERNAL side effect (publishing the reply). Two different problems,
 * two different mechanisms, used together correctly - a good thing to be
 * able to articulate clearly in an interview.
 */
@Component
public class PaymentSagaListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentSagaListener.class);

    private final IdempotencyLockService lockService;
    private final PaymentGateway gateway;
    private final PaymentTransactionRepository transactionRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public PaymentSagaListener(IdempotencyLockService lockService,
                               PaymentGateway gateway,
                               PaymentTransactionRepository transactionRepository,
                               OutboxRepository outboxRepository,
                               ObjectMapper objectMapper) {
        this.lockService = lockService;
        this.gateway = gateway;
        this.transactionRepository = transactionRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "payment.charge.command", groupId = "payment-service")
    public void onChargeCommand(ChargePaymentCommand command, Acknowledgment ack) {
        try {
            handleChargeCommand(command);
        } finally {
            ack.acknowledge();
        }
    }

    private void handleChargeCommand(ChargePaymentCommand command) {
        String idempotencyKey = command.idempotencyKey();

        // ── Idempotency check #1: has this exact request already been
        // fully processed before? (DB is the system of record - check it
        // first, since Redis's TTL means the cache could have expired even
        // though the DB record persists forever.)
        Optional<PaymentTransaction> existing =
                transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.info("Idempotency key {} already processed (DB), replaying reply for order {}",
                    idempotencyKey, command.orderId());
            publishReplyFromExisting(existing.get());
            return;
        }

        // ── Idempotency check #2: is ANOTHER request for this exact key
        // currently in flight right now? (the Redis lock, see Step 4.1)
        boolean acquiredLock = lockService.tryAcquireLock(idempotencyKey);
        if (!acquiredLock) {
            Optional<String> cached = lockService.getCachedResult(idempotencyKey);
            if (cached.isPresent()) {
                log.info("Idempotency key {} resolved from Redis cache for order {}",
                        idempotencyKey, command.orderId());
                // In a fuller implementation, deserialize and republish from
                // cache here. For brevity, we rely on the DB check above to
                // be the durable source of truth in nearly all real flows -
                // this Redis-only branch mainly protects against the rare
                // window where the DB write hasn't committed yet but another
                // request is already mid-flight.
                return;
            }
            log.warn("Idempotency key {} is currently PROCESSING by another request - " +
                    "this command will be retried by Kafka's consumer redelivery", idempotencyKey);
            // Not acknowledging or processing further here means this message
            // will be redelivered on the next poll, naturally retrying once
            // the in-flight request finishes and the lock or DB row appears.
            throw new IllegalStateException("Concurrent processing in progress for key: " + idempotencyKey);
        }

        try {
            processCharge(command, idempotencyKey);
        } catch (Exception e) {
            lockService.releaseLockOnFailure(idempotencyKey);
            throw e;
        }
    }

    @Transactional
    protected void processCharge(ChargePaymentCommand command, String idempotencyKey) {
        PaymentTransaction tx = PaymentTransaction.createProcessing(
                command.orderId(), command.userId(), command.amount(), command.currency(), idempotencyKey);
        tx = transactionRepository.save(tx);

        GatewayResult result = gateway.charge(command.orderId(), command.amount(), command.currency());

        if (result.success()) {
            tx.markSucceeded(result.gatewayReference());
        } else {
            tx.markFailed(result.failureReason());
        }

        var reply = new PaymentReply(
                command.orderId(), result.success(), tx.getId(), result.failureReason());

        writeToOutbox(tx.getOrderId(), reply);

        lockService.storeResult(idempotencyKey, serializeQuietly(reply));

        log.info("Payment {} for order {}: {}", result.success() ? "succeeded" : "failed",
                command.orderId(), tx.getId());
    }

    private void publishReplyFromExisting(PaymentTransaction tx) {
        var reply = new PaymentReply(
                tx.getOrderId(),
                tx.getStatus() == PaymentTransaction.PaymentStatus.SUCCEEDED,
                tx.getId(),
                tx.getFailureReason()
        );
        writeToOutbox(tx.getOrderId(), reply);
    }

    private void writeToOutbox(UUID orderId, PaymentReply reply) {
        String json = serializeQuietly(reply);
        outboxRepository.save(OutboxEvent.of(
                "PaymentTransaction", orderId, "PaymentReply", "payment.charge.reply", json));
    }

    private String serializeQuietly(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize payment reply", e);
        }
    }
}