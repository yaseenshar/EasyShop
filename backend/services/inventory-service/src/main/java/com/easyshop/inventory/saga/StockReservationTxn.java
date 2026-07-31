package com.easyshop.inventory.saga;

import com.easyshop.common.saga.SagaMessages.StockConfirmationReply;
import com.easyshop.common.saga.SagaMessages.StockReservationReply;
import com.easyshop.inventory.entity.ProductStock;
import com.easyshop.inventory.entity.StockReservation;
import com.easyshop.inventory.outbox.OutboxEvent;
import com.easyshop.inventory.outbox.OutboxRepository;
import com.easyshop.inventory.repository.ProductStockRepository;
import com.easyshop.inventory.repository.StockReservationRepository;
import com.easyshop.inventory.saga.StockReservationService.ReservationOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * INNER transactional bean - the canonical transient-DB-failure retry case.
 *
 * Under a flash sale, many threads read the same product_stock row, and its
 * @Version makes all but one writer fail with
 * ObjectOptimisticLockingFailureException on commit. That failure is
 * TRANSIENT: re-read the fresh row, re-apply, and it succeeds - exactly what
 * retry is for, and exactly what pessimistic locking would have serialised
 * into a queue instead.
 *
 * WHY THIS IS TWO BEANS, NOT ONE METHOD: a retry of an optimistic-lock
 * failure MUST run in a FRESH TRANSACTION each attempt. If @Retry and
 * @Transactional sit on the SAME method, the first failure marks the
 * transaction rollback-only and poisons the persistence context; every retry
 * then re-runs inside that same doomed transaction with a STALE @Version and
 * fails forever. Retry has to re-enter through the transactional proxy so
 * each attempt gets a new transaction AND a fresh entity read - the same
 * "cross a bean boundary" rule that makes any Spring AOP interceptor
 * (@Transactional, @Retry, @Cacheable...) actually fire instead of being
 * silently skipped by a self-invocation.
 *
 * Propagation.REQUIRES_NEW guarantees a genuinely fresh transaction per
 * attempt even if some future caller ever wraps this call in an outer
 * transaction.
 */
@Slf4j
@Service
public class StockReservationTxn {

    private final ProductStockRepository stockRepository;
    private final StockReservationRepository reservationRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public StockReservationTxn(ProductStockRepository stockRepository,
                                StockReservationRepository reservationRepository,
                                OutboxRepository outboxRepository,
                                ObjectMapper objectMapper) {
        this.stockRepository = stockRepository;
        this.reservationRepository = reservationRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReservationOutcome reserve(UUID orderId, UUID productId, int quantity) {
        var stockOpt = stockRepository.findByProductId(productId);
        if (stockOpt.isEmpty()) {
            log.warn("Product not found for reservation: {}", productId);
            return new ReservationOutcome(false, null, "Product not found: " + productId);
        }
        ProductStock stock = stockOpt.get();

        // BUSINESS failure - NOT transient. A normal return value, never an
        // exception, so it can never be retried regardless of the outer
        // bean's retryExceptions config. Insufficient stock is a real answer
        // the saga acts on (compensate/cancel), not a blip to paper over.
        if (!stock.tryReserve(quantity)) {
            log.info("Insufficient stock for productId: {} (requested {}, available {})",
                    productId, quantity, stock.getAvailableQty());
            return new ReservationOutcome(false, null,
                    "Insufficient stock: requested " + quantity + ", available " + stock.getAvailableQty());
        }

        // On commit, a concurrent writer that won the race makes this flush
        // throw ObjectOptimisticLockingFailureException - the transient signal
        // the outer @Retry catches and re-drives with a fresh read. That
        // exception surfaces at the transaction boundary (commit), i.e. as
        // this method returns through the proxy.
        stockRepository.save(stock);

        StockReservation reservation = StockReservation.createNew(orderId, productId, quantity);
        reservation = reservationRepository.save(reservation);

        log.info("Reserved {} units of product {} for order {} (reservation {})",
                quantity, productId, orderId, reservation.getId());

        return new ReservationOutcome(true, reservation.getId(), null);
    }

    // Same @Version'd product_stock row, same transient-contention exposure as
    // reserve() above - a concurrent confirm/release/reserve on the same
    // product can lose the optimistic-lock race here too (observed live).
    // REQUIRES_NEW for the same reason: a retried attempt needs a fresh
    // transaction and a fresh read, not the poisoned one from the failed try.
    //
    // The outbox write below is INSIDE this same transaction on purpose -
    // unlike reserve() (one REQUIRES_NEW transaction PER LINE ITEM, so no
    // single one of them can be atomic with the one order-level reply),
    // every row touched here belongs to this order and already commits as
    // one unit, so folding the reply in closes the exact dual-write gap
    // PaymentChargeAndRefundTxn already closes for payment-service: without
    // this, a crash between "stock confirmed" and "reply published" left
    // the order stuck in CONFIRMING_STOCK forever with the customer already
    // charged and the stock already (permanently) decremented.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void confirmOrder(UUID orderId) {
        List<StockReservation> reservations = reservationRepository.findAllByOrderId(orderId);
        for (StockReservation reservation : reservations) {
            var stock = stockRepository.findByProductId(reservation.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("Product not found: " + reservation.getProductId()));

            stock.confirmReservation(reservation.getQuantity());
            stockRepository.save(stock);

            reservation.confirm();

            log.info("Confirmed reservation(s) {} for order {}", reservation.getId(), orderId);
        }

        writeReply(orderId, new StockConfirmationReply(orderId, true, null),
                "inventory.stock-confirmation.reply");
    }

    // No outbox write here on purpose - this overload also serves
    // onReleaseCommand's payment-failure compensation path, which
    // order-service never listens for a reply on (see SagaTopics'
    // INVENTORY_RELEASE_COMMAND javadoc). Use
    // releaseOrderAndReportReservationFailure below when a reply IS needed.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseOrder(UUID orderId) {
        releaseReservations(orderId);
    }

    // Closes the same dual-write gap as confirmOrder(), for
    // handleReserveCommand's partial-failure cleanup: release whatever this
    // order already reserved, THEN report the reservation as failed - both
    // in the one transaction, so a crash can't strand "already released"
    // and "reply never sent" across two separately-committed steps.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseOrderAndReportReservationFailure(UUID orderId, String failureReason) {
        releaseReservations(orderId);
        writeReply(orderId, new StockReservationReply(orderId, false, null, failureReason),
                "inventory.stock-reservation.reply");
    }

    private void releaseReservations(UUID orderId) {
        List<StockReservation> reservations = reservationRepository.findAllByOrderId(orderId);
        for (StockReservation reservation : reservations) {
            if (reservation.getStatus() != StockReservation.ReservationStatus.RESERVED) {
                continue;
            }

            var stock = stockRepository.findByProductId(reservation.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("Product not found: " + reservation.getProductId()));

            stock.releaseReservation(reservation.getQuantity());
            stockRepository.save(stock);
            reservation.release();
        }

        log.info("Released {} reservation(s) for order {}", reservations.size(), orderId);
    }

    private void writeReply(UUID orderId, Object reply, String topic) {
        try {
            String payload = objectMapper.writeValueAsString(reply);
            outboxRepository.save(OutboxEvent.of("StockReservation", orderId,
                    reply.getClass().getSimpleName(), topic, payload));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize inventory saga reply", e);
        }
    }
}
