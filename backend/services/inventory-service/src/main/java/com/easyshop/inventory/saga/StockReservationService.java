package com.easyshop.inventory.saga;

import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * OUTER bean: owns the retry, owns NO transaction. Every call below crosses
 * the proxy boundary into txn's brand-new transaction - see
 * StockReservationTxn's javadoc for why that split is required.
 *
 * confirmOrder/releaseOrder mutate the SAME @Version'd product_stock row as
 * reserve() and are equally exposed to concurrent-writer contention (proven
 * live: concurrent confirm/release calls for the same product raced and threw
 * ObjectOptimisticLockingFailureException) - so they get the identical
 * retry treatment, not just reserve().
 */
@Slf4j
@Service
public class StockReservationService {

    private final StockReservationTxn txn;

    public StockReservationService(StockReservationTxn txn) {
        this.txn = txn;
    }

    public record ReservationOutcome(boolean success, UUID reservationId, String failureReason) {}

    /**
     * name = "stockReservation" must match the resilience4j.retry.instances
     * key, or it silently uses library defaults (maxAttempts=3, no backoff,
     * AND no exception filtering - meaning it would retry business failures
     * too).
     *
     * Retries ONLY the transient case: ObjectOptimisticLockingFailureException
     * thrown by StockReservationTxn at commit when a concurrent writer won the
     * @Version race. Product-not-found and insufficient-stock are ordinary
     * return values (see ReservationOutcome), never exceptions - they can
     * never be retried regardless of retryExceptions config, which is exactly
     * what makes a real business failure (insufficient stock) safe to leave
     * unfiltered.
     *
     * No fallbackMethod on purpose: if every retry is exhausted, the
     * contention is pathological and the exception should propagate up to
     * onReserveCommand's catch block, which reports it as a failure the saga
     * compensates - not a fake success.
     */
    @Retry(name = "stockReservation")
    public ReservationOutcome reserve(UUID orderId, UUID productId, int quantity) {
        return txn.reserve(orderId, productId, quantity);
    }

    @Retry(name = "stockReservation")
    public void confirmOrder(UUID orderId) {
        txn.confirmOrder(orderId);
    }

    @Retry(name = "stockReservation")
    public void releaseOrder(UUID orderId) {
        txn.releaseOrder(orderId);
    }
}
