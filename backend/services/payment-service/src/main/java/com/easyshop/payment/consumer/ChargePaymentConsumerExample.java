// ----------------------------------------------------------------------------
// DOOR 2 — Kafka (payment-service / notification-service). No HTTP request, so
// the interceptor cannot apply. The consumer calls the SAME IdempotencyStore on
// the MESSAGE key, replacing the bespoke SET-NX code that used to live here.
// ----------------------------------------------------------------------------

package com.easyshop.payment.consumer; // illustrative

import java.time.Duration;
import com.easyshop.common.idempotency.IdempotencyStore;
import com.easyshop.common.idempotency.IdempotencyStore.Begin;

class ChargePaymentConsumerExample {

    private final IdempotencyStore store; // injected from common-lib

    ChargePaymentConsumerExample(IdempotencyStore store) { this.store = store; }

    // @KafkaListener(topics = "saga.commands")
    void onChargePayment(/* ChargePaymentCommand cmd */) {
        // The command's own idempotency key (the saga guarantees one per attempt).
        String key = "idem:payment:charge:" + /* cmd.idempotencyKey() */ "KEY";
        String fingerprint = /* hash(cmd) */ "FP";

        Begin begin = store.begin(key, fingerprint, Duration.ofSeconds(60));
        if (!(begin instanceof Begin.Acquired)) {
            // COMPLETED  -> already charged; ack and move on (no double charge).
            // IN_PROGRESS-> a redelivery landed while the first is mid-flight;
            //               let Kafka redeliver, or short-circuit — your choice.
            return;
        }
        try {
            // ... perform the irreversible external charge exactly once ...
            // On success, record completion so a Kafka REDELIVERY is deduped:
            store.complete(key, /* IdempotencyRecord.of(200, null, resultBytes, fingerprint) */ null,
                    Duration.ofHours(24));
        } catch (RuntimeException transientFailure) {
            store.release(key); // allow the redelivery to genuinely retry
            throw transientFailure;
        }
    }

    // NOTE: payment KEEPS its DB unique constraint too — the Redis lock is the
    // fast path, the constraint is the irreversible-charge backstop (§4.8). The
    // ONLY change here is that the SET-NX/result-cache logic is now the shared
    // IdempotencyStore instead of code duplicated per consumer — the same
    // "lift into common-lib" move as SagaMessages and the outbox (§4.14).
}