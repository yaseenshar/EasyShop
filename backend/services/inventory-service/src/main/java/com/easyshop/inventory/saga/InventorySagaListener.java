package com.easyshop.inventory.saga;


import com.easyshop.common.saga.SagaMessages.*;
import com.easyshop.inventory.outbox.OutboxEvent;
import com.easyshop.inventory.outbox.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class InventorySagaListener {

    private final StockReservationService reservationService;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public InventorySagaListener(StockReservationService reservationService, OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.reservationService = reservationService;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    // concurrency=3 (matches the topic's 3 partitions, see order-service's
    // KafkaConfig#inventoryReserveCommandTopic): the default concurrency=1
    // serializes every reserve command onto a single thread, which means two
    // orders for the SAME product can never actually race on product_stock's
    // @Version - the exact contention @Retry above exists to handle (verified
    // live: with concurrency=1, 20 concurrent checkouts produced zero retry
    // events because inventory-service processed them one at a time).
    // Messages are keyed by orderId (OutboxPublisher), so different orders
    // for the same product land on different partitions/threads and can
    // genuinely overlap.
    @KafkaListener(topics = "inventory.reserve-stock.command", groupId = "inventory-service", concurrency = "3")
    public void onReserveCommand(ReserveStockCommand command, Acknowledgment ack) {
        try {
            handleReserveCommand(command);
        } catch (Exception e) {
            log.error("Unexpected failure reserving stock for order {}", command.orderId(), e);
            publishReply(command.orderId(), false, null, e.getMessage());
        } finally {
            ack.acknowledge();
        }
    }


    private void handleReserveCommand(ReserveStockCommand command) {

        List<UUID> successfulReservationIds = new ArrayList<>();
        for (ReserveStockCommand.LineItem item : command.items()) {

            var outcome = reservationService.reserve(command.orderId(), item.productId(), item.quantity());

            if (!outcome.success()) {
                log.warn("Reservation failed for order {} on product {}: {} - releasing {} already-reserved item(s) from this same order",
                        command.orderId(), item.productId(), outcome.failureReason(), successfulReservationIds.size());

                if (!successfulReservationIds.isEmpty()) {
                    reservationService.releaseOrder(command.orderId());
                }

                publishReply(command.orderId(), false, null, outcome.failureReason());
                return;
            }


            successfulReservationIds.add(outcome.reservationId());
        }
        publishReply(command.orderId(), true, successfulReservationIds.getFirst(), null);

    }

    @KafkaListener(topics = "inventory.confirm-stock.command", groupId = "inventory-service")
    public void onConfirmCommand(ConfirmStockCommand command, Acknowledgment ack) {
        try {
            reservationService.confirmOrder(command.orderId());
            var reply = new StockConfirmationReply(command.orderId(), true, null);
            writeReply("inventory.stock-confirmation.reply", command.orderId(), reply);
        } catch (Exception e) {
            log.error("Failed to confirm stock for order {}", command.orderId(), e);
            var reply = new StockConfirmationReply(command.orderId(), false, e.getMessage());
            writeReply("inventory.stock-confirmation.reply", command.orderId(), reply);
        } finally {
            ack.acknowledge();
        }
    }

    @KafkaListener(topics = "inventory.release-stock.command", groupId = "inventory-service")
    public void onReleaseCommand(ReleaseStockCommand command, Acknowledgment ack) {
        try {
            reservationService.releaseOrder(command.orderId());
            log.info("Released stock for order {} due to saga compensation", command.orderId());
        } catch (Exception e) {
            log.error("Failed to release stock for order {}", command.orderId(), e);
        } finally {
            ack.acknowledge();
        }
    }

    private void publishReply(UUID orderId, boolean success, UUID reservationId, String failureReason) {

        var reply = new StockReservationReply(orderId, success, reservationId, failureReason);
        writeReply("inventory.stock-reservation.reply", orderId, reply);
    }

    private void writeReply(String topic, UUID orderId, Object reply) {
        try {
            String payload = objectMapper.writeValueAsString(reply);
            outboxRepository.save(OutboxEvent.of("StockReservation", orderId,
                    reply.getClass().getSimpleName(), topic, payload));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize inventory saga reply", e);
        }
    }
}
