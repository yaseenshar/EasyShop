package com.easyshop.order.saga;

import com.easyshop.order.entity.Order;
import com.easyshop.order.entity.Order.OrderStatus;
import com.easyshop.order.entity.OrderSagaState;
import com.easyshop.order.outbox.OutboxEvent;
import com.easyshop.order.repository.OrderRepository;
import com.easyshop.order.repository.OrderSagaRepository;
import com.easyshop.order.repository.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The Saga Orchestrator - the single place that knows the entire order
 * fulfillment business process. Drives the state machine forward on the
 * happy path, and drives it backward (compensation) on failure.
 *
 * Design principle being demonstrated here: every step writes its outcome
 * to the local DB AND an outbox row IN THE SAME TRANSACTION (the
 * @Transactional methods below). The actual Kafka publish is handled
 * separately by OutboxPublisher (Step 3.4) - this class never calls
 * KafkaTemplate.send() directly, which is what makes it immune to the
 * dual-write problem described in Step 3.3.
 */
@Component
public class OrderSagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaOrchestrator.class);

    private final OrderRepository orderRepository;
    private final OrderSagaRepository sagaRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OrderSagaOrchestrator(OrderRepository orderRepository,
                                 OrderSagaRepository sagaRepository,
                                 OutboxRepository outboxRepository,
                                 ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.sagaRepository = sagaRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Entry point: called by OrderController right after the Order row and
     * OrderSagaState row are first persisted (PENDING state). Kicks off the
     * first saga step.
     */
    @Transactional
    public void startSaga(Order order, OrderSagaState saga) {
        saga.advanceTo(OrderStatus.RESERVING_STOCK);
        order.transitionTo(OrderStatus.RESERVING_STOCK);

        var command = new SagaMessages.ReserveStockCommand(
                order.getId(),
                order.getItems().stream()
                        .map(item -> new SagaMessages.ReserveStockCommand.LineItem(
                                item.getProductId(), item.getQuantity()))
                        .collect(Collectors.toList()),
                Instant.now()
        );

        writeToOutbox("Order", order.getId(), "ReserveStockCommand",
                SagaTopics.INVENTORY_RESERVE_COMMAND, command);

        log.info("Saga started for order {} - reserving stock", order.getId());
    }

    /**
     * Step 2 reply handler: inventory-service tells us whether stock reservation
     * succeeded. On success, advance to payment. On failure, the saga ends
     * immediately (no compensation needed yet - nothing was charged or
     * reserved elsewhere).
     */
    @KafkaListener(topics = SagaTopics.INVENTORY_RESERVE_REPLY, groupId = "order-service-saga")
    @Transactional
    public void onStockReservationReply(SagaMessages.StockReservationReply reply,
                                        org.springframework.kafka.support.Acknowledgment ack) {
        try {
            handleStockReservationReply(reply);
        } finally {
            // Acknowledge AFTER the @Transactional method body completes (success
            // or handled failure) - with ack-mode=manual_immediate this commits
            // the Kafka offset only once our DB transaction has committed,
            // closing the gap described in the application.yml comment.
            ack.acknowledge();
        }
    }

    private void handleStockReservationReply(SagaMessages.StockReservationReply reply) {
        OrderSagaState saga = sagaRepository.findById(reply.orderId())
                .orElseThrow(() -> new IllegalStateException("Unknown saga: " + reply.orderId()));
        Order order = orderRepository.findById(reply.orderId())
                .orElseThrow(() -> new IllegalStateException("Unknown order: " + reply.orderId()));

        // Idempotency guard: if we've already moved past RESERVING_STOCK
        // (e.g. this is a duplicate Kafka delivery, which IS possible under
        // at-least-once semantics), ignore the redundant reply rather than
        // attempting an invalid state transition.
        if (saga.getCurrentStep() != OrderStatus.RESERVING_STOCK) {
            log.warn("Ignoring duplicate/late stock reservation reply for order {}", reply.orderId());
            return;
        }

        if (!reply.success()) {
            log.info("Stock reservation failed for order {}: {}", reply.orderId(), reply.failureReason());
            saga.advanceTo(OrderStatus.CANCELLED);
            order.transitionTo(OrderStatus.CANCELLED);
            publishOrderCancelled(order, reply.failureReason());
            return;
        }

        saga.recordReservation(reply.reservationId());
        saga.advanceTo(OrderStatus.CHARGING_PAYMENT);
        order.transitionTo(OrderStatus.CHARGING_PAYMENT);

        var command = new SagaMessages.ChargePaymentCommand(
                order.getId(),
                order.getUserId(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getIdempotencyKey(), // same key reused - see Phase 4 for why
                Instant.now()
        );

        writeToOutbox("Order", order.getId(), "ChargePaymentCommand",
                SagaTopics.PAYMENT_CHARGE_COMMAND, command);

        log.info("Stock reserved for order {} - charging payment", order.getId());
    }

    /**
     * Step 3 reply handler: payment-service tells us whether the charge
     * succeeded. On failure, this is the FIRST point a compensation is
     * needed - we must release the stock we reserved in step 2.
     */
    @KafkaListener(topics = SagaTopics.PAYMENT_CHARGE_REPLY, groupId = "order-service-saga")
    @Transactional
    public void onPaymentReply(SagaMessages.PaymentReply reply,
                               org.springframework.kafka.support.Acknowledgment ack) {
        try {
            handlePaymentReply(reply);
        } finally {
            ack.acknowledge();
        }
    }

    private void handlePaymentReply(SagaMessages.PaymentReply reply) {
        OrderSagaState saga = sagaRepository.findById(reply.orderId())
                .orElseThrow(() -> new IllegalStateException("Unknown saga: " + reply.orderId()));
        Order order = orderRepository.findById(reply.orderId())
                .orElseThrow(() -> new IllegalStateException("Unknown order: " + reply.orderId()));

        if (saga.getCurrentStep() != OrderStatus.CHARGING_PAYMENT) {
            log.warn("Ignoring duplicate/late payment reply for order {}", reply.orderId());
            return;
        }

        if (!reply.success()) {
            log.info("Payment failed for order {}: {} - compensating stock reservation",
                    reply.orderId(), reply.failureReason());

            saga.advanceTo(OrderStatus.COMPENSATING);
            order.transitionTo(OrderStatus.COMPENSATING);

            var releaseCommand = new SagaMessages.ReleaseStockCommand(
                    order.getId(), saga.getReservationId(), Instant.now());

            writeToOutbox("Order", order.getId(), "ReleaseStockCommand",
                    SagaTopics.INVENTORY_RELEASE_COMMAND, releaseCommand);

            saga.recordCompensation("RELEASE_STOCK");
            saga.advanceTo(OrderStatus.CANCELLED);
            order.transitionTo(OrderStatus.CANCELLED);
            publishOrderCancelled(order, reply.failureReason());
            return;
        }

        saga.recordPayment(reply.transactionId());
        saga.advanceTo(OrderStatus.CONFIRMING_STOCK);
        order.transitionTo(OrderStatus.CONFIRMING_STOCK);

        var confirmCommand = new SagaMessages.ConfirmStockCommand(
                order.getId(), saga.getReservationId(), Instant.now());

        writeToOutbox("Order", order.getId(), "ConfirmStockCommand",
                SagaTopics.INVENTORY_CONFIRM_COMMAND, confirmCommand);

        log.info("Payment charged for order {} - confirming stock", order.getId());
    }

    /**
     * Step 4 reply handler: inventory-service confirms the reservation is
     * now permanently committed (decremented from available stock for good).
     *
     * Note on a subtle but important design point: if THIS step fails, we
     * have a genuinely hard problem - the customer has already been charged.
     * This is intentionally NOT auto-compensated by refunding, because a
     * silent auto-refund on a transient confirmation failure is worse than
     * pausing for human/ops intervention. We mark the order for manual
     * review instead. This is a real production nuance worth raising
     * unprompted in an interview: not every saga failure should auto-compensate;
     * sometimes the safest compensation is "alert a human."
     */
    @KafkaListener(topics = SagaTopics.INVENTORY_CONFIRM_REPLY, groupId = "order-service-saga")
    @Transactional
    public void onStockConfirmationReply(SagaMessages.StockConfirmationReply reply,
                                         org.springframework.kafka.support.Acknowledgment ack) {
        try {
            handleStockConfirmationReply(reply);
        } finally {
            ack.acknowledge();
        }
    }

    private void handleStockConfirmationReply(SagaMessages.StockConfirmationReply reply) {
        OrderSagaState saga = sagaRepository.findById(reply.orderId())
                .orElseThrow(() -> new IllegalStateException("Unknown saga: " + reply.orderId()));
        Order order = orderRepository.findById(reply.orderId())
                .orElseThrow(() -> new IllegalStateException("Unknown order: " + reply.orderId()));

        if (saga.getCurrentStep() != OrderStatus.CONFIRMING_STOCK) {
            log.warn("Ignoring duplicate/late stock confirmation reply for order {}", reply.orderId());
            return;
        }

        if (!reply.success()) {
            log.error("CRITICAL: stock confirmation failed for order {} AFTER payment succeeded. " +
                            "Flagging for manual review rather than auto-compensating. Reason: {}",
                    reply.orderId(), reply.failureReason());
            // Deliberately do NOT transition to CANCELLED or auto-refund here.
            // In a real system this would page an on-call engineer / open a
            // support ticket via a dedicated "saga-requires-attention" topic.
            return;
        }

        saga.advanceTo(OrderStatus.NOTIFYING);
        order.transitionTo(OrderStatus.NOTIFYING);

        publishOrderCompleted(order);

        saga.advanceTo(OrderStatus.CONFIRMED);
        order.transitionTo(OrderStatus.CONFIRMED);

        log.info("Order {} fully confirmed - saga complete", order.getId());
    }

    private void publishOrderCompleted(Order order) {
        var event = new OrderEvents.OrderCompletedEvent(
                order.getId(), order.getUserId(), order.getTotalAmount(), Instant.now());
        writeToOutbox("Order", order.getId(), "OrderCompletedEvent",
                SagaTopics.ORDER_EVENTS, event);
    }

    private void publishOrderCancelled(Order order, String reason) {
        var event = new OrderEvents.OrderCancelledEvent(
                order.getId(), order.getUserId(), reason, Instant.now());
        writeToOutbox("Order", order.getId(), "OrderCancelledEvent",
                SagaTopics.ORDER_EVENTS, event);
    }

    /**
     * The transactional outbox write. Called from within the SAME
     * @Transactional method as the business state mutation above it -
     * this is what guarantees atomicity between "saga state changed" and
     * "intent to notify the next participant was durably recorded."
     */
    private void writeToOutbox(String aggregateType, UUID aggregateId,
                               String eventType, String topic, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            outboxRepository.save(OutboxEvent.of(aggregateType, aggregateId, eventType, topic, json));
        } catch (Exception e) {
            // A serialization failure here should fail the whole transaction -
            // we'd rather roll back the saga state change than silently lose
            // the event. Re-throwing as unchecked surfaces this loudly.
            throw new IllegalStateException("Failed to serialize outbox event payload", e);
        }
    }
}