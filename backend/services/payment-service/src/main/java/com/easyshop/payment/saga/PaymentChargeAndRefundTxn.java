package com.easyshop.payment.saga;

import java.time.Instant;
import java.util.UUID;

import com.easyshop.common.event.PaymentEvents.PaymentCompletedEvent;
import com.easyshop.common.event.PaymentEvents.PaymentFailedEvent;
import com.easyshop.common.saga.SagaIdempotencyKeys;
import com.easyshop.common.saga.SagaMessages.ChargePaymentCommand;
import com.easyshop.payment.entity.PaymentTransaction;
import com.easyshop.payment.gateway.PaymentGateway;
import com.easyshop.payment.gateway.PaymentGateway.GatewayResult;
import com.easyshop.payment.outbox.OutboxEvent;
import com.easyshop.payment.outbox.OutboxRepository;
import com.easyshop.payment.repository.PaymentTransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * INNER transactional bean for PaymentSagaListener/RefundSagaListener - the
 * same "two beans, not one method" split as inventory-service's
 * StockReservationService/StockReservationTxn (see that class's javadoc for
 * the full rationale). @Transactional only actually fires when the call
 * crosses a Spring AOP proxy boundary; the previous version of this code had
 * the listener call this work via plain self-invocation (this.processCharge(...)
 * within the same class), which silently skips the proxy and means
 * @Transactional never applied - reintroducing the exact dual-write gap the
 * Transactional Outbox pattern exists to close (the PaymentTransaction save
 * and the outbox write must commit atomically together, or a crash between
 * them leaves a charge recorded with no reply ever published, or vice versa).
 */
@Service
public class PaymentChargeAndRefundTxn {

    private static final Logger log = LoggerFactory.getLogger(PaymentChargeAndRefundTxn.class);

    private final PaymentGateway gateway;
    private final PaymentTransactionRepository transactionRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public PaymentChargeAndRefundTxn(PaymentGateway gateway,
                                     PaymentTransactionRepository transactionRepository,
                                     OutboxRepository outboxRepository,
                                     ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.transactionRepository = transactionRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Object charge(ChargePaymentCommand command, String commandId) {
        // commandId, not command.idempotencyKey() - the field name on the
        // entity/DB column is unchanged (idempotency_key), but the VALUE it
        // now stores is the payment-command-level key, not the order's
        // client-level key. UNIQUE(idempotency_key) fires here on a race.
        PaymentTransaction tx = PaymentTransaction.createProcessing(
                command.orderId(), command.userId(), command.amount(), command.currency(), commandId);
        tx = transactionRepository.save(tx);

        GatewayResult result = gateway.charge(
                command.orderId(), command.amount(), command.currency(), SagaIdempotencyKeys.pspKey(commandId));

        Object reply;
        if (result.success()) {
            tx.markSucceeded(result.gatewayReference());
            reply = new PaymentCompletedEvent(command.orderId(), tx.getId(), Instant.now());
            writeToOutbox(tx.getOrderId(), "PaymentCompletedEvent", "payment.charge.reply", reply);
        } else {
            tx.markFailed(result.failureReason());
            reply = new PaymentFailedEvent(command.orderId(), result.failureReason(), Instant.now());
            writeToOutbox(tx.getOrderId(), "PaymentFailedEvent", "payment.charge.reply", reply);
        }

        log.info("Payment {} for order {}: {}", result.success() ? "succeeded" : "failed",
                command.orderId(), tx.getId());
        return reply;
    }

    @Transactional
    public void refund(UUID orderId, UUID originalTransactionId, String commandId) {
        PaymentTransaction tx = transactionRepository.findById(originalTransactionId)
                .orElseThrow(() -> new IllegalStateException(
                        "No payment transaction " + originalTransactionId + " to refund"));

        GatewayResult result = gateway.refund(originalTransactionId, SagaIdempotencyKeys.refundPspKey(commandId));

        if (!result.success()) {
            // Not modeled as a PaymentTransaction status (unlike a charge
            // decline, which is a common, expected outcome) - a refund
            // decline against a transaction we already successfully charged
            // is unusual enough to warrant a human, not a state transition
            // we'd need to reason about later. See the listener's catch for
            // how this surfaces (logged loudly, not silently retried forever).
            throw new IllegalStateException("Refund declined by gateway: " + result.failureReason());
        }

        // markRefunded()'s "only from SUCCEEDED" guard + @Version together are
        // the DB-level backstop (layer 2): a concurrent second refund attempt
        // either finds status already REFUNDED (IllegalStateException) or
        // loses the optimistic-lock race (ObjectOptimisticLockingFailureException)
        // - the listener treats either as "already handled", not a failure.
        tx.markRefunded(result.gatewayReference());

        log.info("Refunded transaction {} for order {}: {}", originalTransactionId, orderId, result.gatewayReference());
    }

    private void writeToOutbox(UUID orderId, String eventType, String topic, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            outboxRepository.save(OutboxEvent.of("PaymentTransaction", orderId, eventType, topic, json));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize outbox event payload", e);
        }
    }
}
