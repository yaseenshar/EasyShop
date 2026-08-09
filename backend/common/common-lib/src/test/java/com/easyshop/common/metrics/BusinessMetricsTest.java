package com.easyshop.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole point of BusinessMetrics is what happens around a transaction
 * boundary, so that is what these assert. A test that only checked "increment
 * increments" would pass against a plain registry.counter() call and prove
 * nothing about why this class exists.
 */
class BusinessMetricsTest {

    private MeterRegistry registry;
    private BusinessMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new BusinessMetrics(registry);
        TransactionSynchronizationManager.clear();
    }

    @Test
    void countsImmediatelyWhenThereIsNoTransaction() {
        // cart-service has no transaction manager at all - if this deferred,
        // its counters would never fire.
        metrics.increment("easyshop.test.thing", "outcome", "ok");

        assertThat(count("easyshop.test.thing", "outcome", "ok")).isEqualTo(1.0);
    }

    @Test
    void doesNotCountBeforeTheTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            metrics.increment("easyshop.test.thing", "outcome", "ok");

            // Still in-flight: the database has not agreed to anything yet.
            assertThat(count("easyshop.test.thing", "outcome", "ok")).isZero();
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void countsOnceTheTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            metrics.increment("easyshop.test.thing", "outcome", "ok");
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCommit());
        } finally {
            TransactionSynchronizationManager.clear();
        }

        assertThat(count("easyshop.test.thing", "outcome", "ok")).isEqualTo(1.0);
    }

    /**
     * The regression that justifies the class: a rolled-back saga step must not
     * report an outcome. Without the afterCommit deferral this reads 1.0, and
     * the metric permanently disagrees with the database.
     */
    @Test
    void doesNotCountWhenTheTransactionRollsBack() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            metrics.increment("easyshop.orders.saga", "outcome", "completed");
            // Rollback: afterCommit is never invoked, only afterCompletion.
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(
                            org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clear();
        }

        assertThat(count("easyshop.orders.saga", "outcome", "completed")).isZero();
    }

    /**
     * The series must exist even at zero. "No data" and "0 failures" look
     * identical on a dashboard until you need them not to.
     */
    @Test
    void theSeriesIsRegisteredEvenBeforeTheFirstCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            metrics.increment("easyshop.test.pending", "outcome", "ok");
        } finally {
            TransactionSynchronizationManager.clear();
        }

        assertThat(registry.find("easyshop.test.pending").counter())
                .as("meter should be registered at increment time, not at commit time")
                .isNotNull();
    }

    @Test
    void separateTagValuesAreSeparateSeries() {
        metrics.increment("easyshop.orders.saga", "outcome", "completed", "stage", "none");
        metrics.increment("easyshop.orders.saga", "outcome", "cancelled", "stage", "payment");
        metrics.increment("easyshop.orders.saga", "outcome", "cancelled", "stage", "payment");

        assertThat(count("easyshop.orders.saga", "outcome", "completed", "stage", "none")).isEqualTo(1.0);
        assertThat(count("easyshop.orders.saga", "outcome", "cancelled", "stage", "payment")).isEqualTo(2.0);
    }

    private double count(String name, String... tags) {
        var counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
