package com.easyshop.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Counters for business outcomes, recorded only when the surrounding
 * transaction actually commits.
 *
 * WHY THIS EXISTS RATHER THAN registry.counter(..).increment(). Business
 * outcomes are decided inside @Transactional methods - "the order completed",
 * "the charge failed" - and a plain increment fires the moment the line runs,
 * whether or not that transaction goes on to commit. A rolled-back checkout
 * would still report an order completed. The result is a metric that disagrees
 * with the database, and the disagreement shows up precisely during incidents,
 * when transactions are failing and the numbers matter most.
 *
 * This is the same hazard the catalog cache solved with transactionAware() - an
 * effect outside the database being applied before the database agreed to it -
 * and it gets the same answer: defer to afterCommit.
 *
 * WITH NO TRANSACTION ACTIVE the increment happens immediately, which is what
 * makes this safe to call from cart-service (Redis-backed, deliberately no
 * transaction manager) and from Kafka listeners that have already committed.
 *
 * KEEP TAG VALUES BOUNDED. Every distinct combination of tag values is a
 * separate time series in Prometheus, held in memory in the process and again
 * in the TSDB. Tag with outcomes, reasons and statuses - things drawn from a
 * small fixed set. Never with an order id, a user id, a product id or a raw
 * exception message: those are unbounded, and the failure mode is a slow
 * memory leak in every instrumented service rather than an obvious error.
 */
public class BusinessMetrics {

    private final MeterRegistry registry;

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Increments a counter, deferring until commit when a transaction is active.
     *
     * @param name dot-separated meter name, e.g. "easyshop.orders.saga"
     * @param tags alternating key/value pairs, values from a SMALL fixed set
     */
    public void increment(String name, String... tags) {
        // Resolved now, not in the callback: registry lookups are cheap and
        // cached, and doing it here means the series exists (at zero) as soon as
        // the code path is reached rather than only after the first commit - so
        // a dashboard panel shows 0 instead of "No data", which are very
        // different messages during an incident.
        Counter counter = registry.counter(name, Tags.of(tags));

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            counter.increment();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                counter.increment();
            }
        });
    }
}
