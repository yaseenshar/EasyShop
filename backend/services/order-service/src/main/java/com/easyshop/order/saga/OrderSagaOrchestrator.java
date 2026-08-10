package com.easyshop.order.saga;

import com.easyshop.common.event.OrderCreatedEvent;
import com.easyshop.common.metrics.BusinessMetrics;
import com.easyshop.common.event.OrderEvents;
import com.easyshop.common.event.PaymentEvents.PaymentCompletedEvent;
import com.easyshop.common.event.PaymentEvents.PaymentFailedEvent;
import com.easyshop.common.saga.SagaMessages;
import com.easyshop.order.dto.OrderDtos.CreateOrderRequest;
import com.easyshop.order.entity.Order;
import com.easyshop.order.entity.Order.OrderStatus;
import com.easyshop.order.entity.OrderSagaState;
import com.easyshop.order.outbox.OutboxEvent;
import com.easyshop.order.repository.OrderRepository;
import com.easyshop.order.repository.OrderSagaRepository;
import com.easyshop.order.repository.OutboxRepository;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    /** One meter for every saga terminal state; the outcome tag distinguishes them. */
    private static final String SAGA_OUTCOMES = "easyshop.orders.saga";

    private final OrderRepository orderRepository;
    private final OrderSagaRepository sagaRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final BusinessMetrics businessMetrics;

    public OrderSagaOrchestrator(OrderRepository orderRepository,
                                 OrderSagaRepository sagaRepository,
                                 OutboxRepository outboxRepository,
                                 ObjectMapper objectMapper,
                                 BusinessMetrics businessMetrics) {
        this.orderRepository = orderRepository;
        this.sagaRepository = sagaRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.businessMetrics = businessMetrics;

        // Every saga outcome exists as a zero series from startup, so the
        // dashboard shows "0 cancelled" rather than "No data" before the first
        // checkout - see BusinessMetrics.preRegister for why that distinction
        // matters more than it looks. The set is exhaustive and bounded: the
        // three outcomes, and the two stages a saga can die at.
        businessMetrics.preRegister(SAGA_OUTCOMES, "outcome", "started", "stage", "none");
        businessMetrics.preRegister(SAGA_OUTCOMES, "outcome", "completed", "stage", "none");
        businessMetrics.preRegister(SAGA_OUTCOMES, "outcome", "cancelled", "stage", "stock_reservation");
        businessMetrics.preRegister(SAGA_OUTCOMES, "outcome", "cancelled", "stage", "payment");
    }

    /**
     * Entry point: creates the Order + OrderSagaState rows and starts the
     * saga, all in ONE transaction (layer 1 of the checkout idempotency
     * design - see OrderController#createOrder's javadoc for layers 2/3).
     *
     * WHY THIS LIVES HERE, NOT IN OrderController: the caller needs to catch
     * DataIntegrityViolationException from the idempotency_key UNIQUE
     * constraint and then do a FRESH read via findByIdempotencyKey - but a
     * read after a caught exception in the SAME @Transactional method is
     * poisoned (the same "self-invocation crosses a bean boundary" family of
     * trap as StockReservationTxn, see that class's javadoc): the transaction
     * is already marked rollback-only, so the read would fail with
     * UnexpectedRollbackException at commit instead of returning data. Moving
     * the transactional write into ITS OWN bean method means the exception
     * propagates out of a transaction that's already rolling back cleanly,
     * and the caller's fallback read runs with a clean slate.
     */
    @Transactional
    public Order createOrderAndStartSaga(UUID userId, BigDecimal totalAmount,
                                         CreateOrderRequest request, String idempotencyKey) {
        Order newOrder = Order.createNew(userId, totalAmount, request.shippingAddressId(), idempotencyKey);
        request.items().forEach(item -> newOrder.addItem(item.productId(), item.quantity(), item.unitPrice()));
        Order order = orderRepository.save(newOrder); // idempotency_key UNIQUE fires here on a race

        OrderSagaState saga = OrderSagaState.createNew(order.getId());
        saga = sagaRepository.save(saga);

        startSaga(order, saga);
        return order;
    }

    /**
     * Kicks off the first saga step. Called internally by
     * createOrderAndStartSaga right after the Order/OrderSagaState rows are
     * first persisted (PENDING state) - self-invocation is fine here (unlike
     * the retry case above) because the caller is ALREADY inside the
     * transaction this method needs; there is nothing to gain from a fresh
     * proxy hop, only a fresh transaction we deliberately do NOT want.
     */
    @Transactional
    public void startSaga(Order order, OrderSagaState saga) {
        saga.advanceTo(OrderStatus.RESERVING_STOCK);
        order.transitionTo(OrderStatus.RESERVING_STOCK);

        publishOrderCreated(order);

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
            publishOrderCancelled(order, reply.failureReason(), "stock_reservation");
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
                order.getIdempotencyKey(), // audit trail only now - see the field's javadoc in SagaMessages
                UUID.randomUUID(), // commandId: generated ONCE here, baked into the outbox
                // payload, so every Kafka redelivery of this exact message carries
                // the identical value - what payment-service's dedup depends on.
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
     *
     * payment.charge.reply carries TWO different record shapes
     * (PaymentCompletedEvent and PaymentFailedEvent) - same reason
     * OrderEventsListener (notification-service) reads order.events as raw
     * JSON: the listener parameter type can't flex between two shapes, so
     * we discriminate on structure (PaymentFailedEvent carries
     * "failureReason", PaymentCompletedEvent carries "transactionId").
     *
     * The parameter is the raw ConsumerRecord rather than String: the
     * container factory's JacksonJsonMessageConverter always round-trips
     * the record value through Jackson into the declared parameter type
     * (no passthrough for String), so a String-typed parameter blows up
     * trying to deserialize a JSON object into a Java String. Declaring
     * ConsumerRecord<String, String> makes Spring Kafka skip conversion
     * and hand back the raw value untouched.
     */
    @KafkaListener(topics = SagaTopics.PAYMENT_CHARGE_REPLY, groupId = "order-service-saga")
    @Transactional
    public void onPaymentReply(ConsumerRecord<String, String> record,
                               org.springframework.kafka.support.Acknowledgment ack) {
        try {
            handlePaymentReply(record.value());
        } finally {
            ack.acknowledge();
        }
    }

    private void handlePaymentReply(String payload) {
        UUID orderId;
        boolean success;
        UUID transactionId = null;
        String failureReason = null;
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (node.has("failureReason")) {
                var event = objectMapper.treeToValue(node, PaymentFailedEvent.class);
                orderId = event.orderId();
                success = false;
                failureReason = event.failureReason();
            } else {
                var event = objectMapper.treeToValue(node, PaymentCompletedEvent.class);
                orderId = event.orderId();
                success = true;
                transactionId = event.transactionId();
            }
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Unparseable payment reply", e);
        }

        OrderSagaState saga = sagaRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Unknown saga: " + orderId));
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Unknown order: " + orderId));

        if (saga.getCurrentStep() != OrderStatus.CHARGING_PAYMENT) {
            log.warn("Ignoring duplicate/late payment reply for order {}", orderId);
            return;
        }

        if (!success) {
            log.info("Payment failed for order {}: {} - compensating stock reservation",
                    orderId, failureReason);

            saga.advanceTo(OrderStatus.COMPENSATING);
            order.transitionTo(OrderStatus.COMPENSATING);

            var releaseCommand = new SagaMessages.ReleaseStockCommand(
                    order.getId(), saga.getReservationId(), Instant.now());

            writeToOutbox("Order", order.getId(), "ReleaseStockCommand",
                    SagaTopics.INVENTORY_RELEASE_COMMAND, releaseCommand);

            saga.recordCompensation("RELEASE_STOCK");
            saga.advanceTo(OrderStatus.CANCELLED);
            order.transitionTo(OrderStatus.CANCELLED);
            publishOrderCancelled(order, failureReason, "payment");
            return;
        }

        saga.recordPayment(transactionId);
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

    /**
     * Operator-triggered reversal (OrderController's admin refund endpoint) -
     * NOT part of the automatic saga state machine. Per handleStockConfirmationReply's
     * javadoc, a post-payment failure is deliberately flagged for human review
     * rather than auto-refunded; this is that human's action once they decide
     * a refund IS warranted.
     *
     * @return true if this call published the command, false if a refund was
     *         already requested for this order (idempotent no-op - see
     *         OrderSagaState#markRefundRequested).
     */
    @Transactional
    public boolean triggerRefund(Order order, OrderSagaState saga) {
        if (!saga.markRefundRequested()) {
            log.info("Refund already requested for order {} - not publishing a second command", order.getId());
            return false;
        }

        var command = new SagaMessages.RefundPaymentCommand(
                order.getId(), saga.getPaymentTransactionId(), UUID.randomUUID(), Instant.now());

        writeToOutbox("Order", order.getId(), "RefundPaymentCommand",
                SagaTopics.PAYMENT_REFUND_COMMAND, command);

        log.info("Refund requested for order {} (transaction {})", order.getId(), saga.getPaymentTransactionId());
        return true;
    }

    /**
     * SAGA OUTCOME METRICS are recorded in these three publish* methods rather
     * than at their call sites: each is the single choke point for its outcome,
     * so a future branch that cancels an order cannot forget to count it.
     *
     * ONE COUNTER, TAGGED BY OUTCOME - not three separate counters. It makes the
     * question you actually ask a single query: the rollback RATE is
     * cancelled/(completed+cancelled), which is one expression over one metric
     * name instead of a join across three.
     *
     * THE FAILURE REASON IS NOT A TAG. reply.failureReason() is free text from
     * a downstream service ("Card declined by issuer", and whatever a future
     * gateway invents); as a tag value it would create an unbounded number of
     * time series - the classic way to take Prometheus down with a metric. The
     * bounded "stage" tag answers the question that actually drives action -
     * WHERE the saga died - while the reason stays in the log line above, which
     * is the right place for high-cardinality detail.
     *
     * These fire on afterCommit (see BusinessMetrics), so a saga step whose
     * transaction rolls back does not report an outcome that never happened.
     */
    private void publishOrderCreated(Order order) {
        businessMetrics.increment(SAGA_OUTCOMES, "outcome", "started", "stage", "none");
        var items = order.getItems().stream()
                .map(item -> new OrderCreatedEvent.OrderItem(
                        item.getProductId(), item.getQuantity(), item.getUnitPrice()))
                .collect(Collectors.toList());

        var event = new OrderCreatedEvent(
                UUID.randomUUID(), order.getId(), order.getUserId(), items,
                order.getTotalAmount(), order.getShippingAddressId(), Instant.now());

        writeToOutbox("Order", order.getId(), "OrderCreatedEvent",
                SagaTopics.ORDER_EVENTS, event);
    }

    private void publishOrderCompleted(Order order) {
        businessMetrics.increment(SAGA_OUTCOMES, "outcome", "completed", "stage", "none");
        var event = new OrderEvents.OrderCompletedEvent(
                order.getId(), order.getUserId(), order.getTotalAmount(), Instant.now());
        writeToOutbox("Order", order.getId(), "OrderCompletedEvent",
                SagaTopics.ORDER_EVENTS, event);
    }

    private void publishOrderCancelled(Order order, String reason, String stage) {
        businessMetrics.increment(SAGA_OUTCOMES, "outcome", "cancelled", "stage", stage);
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