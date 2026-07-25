# payment-order-idempotency/ — apply idempotency to POST /orders and payment

**Ticket:** Apply idempotency logic to `POST /orders` and all payment endpoints.

**Builds on** the idempotency-engine ticket (the `IdempotencyStore` primitive +
`@Idempotent` interceptor). This ticket APPLIES them to the two most expensive
duplicates in the system and closes the gaps the engine can't close alone.

---

## Two clarifications that shape the work

1. **Payment has no HTTP endpoints.** It's Kafka-driven and unrouted (§3.1,
   §4.15). "All payment endpoints" = its command handlers — `ChargePaymentCommand`
   and the reversal/`RefundPaymentCommand` path — not REST controllers. So
   payment idempotency is consumer-side, on the message, using `IdempotencyStore`
   directly (the Kafka door), never the HTTP interceptor.

2. **Two key-spaces, kept separate (§4.8).** The order-level key is the client's
   `Idempotency-Key` header (dedupes a browser retry → one order, one saga). The
   payment key is a deterministic saga-command id (dedupes Kafka redelivery →
   charge once). Conflating them is the classic bug; `SagaIdempotencyKeys` keeps
   them distinct.

## POST /orders — three layers, and why the third exists

| Layer | Mechanism | Catches |
|---|---|---|
| 1 | `@Idempotent` (Redis, fast) | the vast majority of browser retries; replays the original 200 |
| 2 | `orders.idempotency_key` UNIQUE (DB) | the window where Redis failed OPEN or the SET-NX/GET race slipped a 2nd request through |
| 3 | **catch `DataIntegrityViolationException` → return the existing order** | turns layer 2 into a correct 200 instead of a 500 |

Layer 3 is the piece the engine does NOT provide and the reason fail-open is safe
on checkout. Because order + `ORDER_CREATED` outbox event are written in one
transaction (§4.7) and the insert commits at most once, the **saga starts at most
once** — HTTP idempotency and exactly-once saga kickoff are one guarantee seen
from two layers.

## Payment — three layers for the one irreversible operation

| Layer | Mechanism |
|---|---|
| 1 | Redis `IdempotencyStore` (replaces the bespoke SET-NX code, §4.8 → shared) |
| 2 | payment DB `UNIQUE(command_id)` backstop |
| 3 | **the PSP's own idempotency key** — dedupes even if we crash after the PSP charges but before we record it |

**Deterministic key** (`SagaIdempotencyKeys.charge(commandId)`): derived from the
outbox event id the orchestrator stamped, so every Kafka redelivery computes the
same key. A random key would charge twice on redelivery — the single most
important correctness point in this ticket.

**Redelivery of an already-charged command is a no-op ack.** The outbox already
emitted `PaymentCompleted` exactly once (§4.7), so the saga already advanced;
redelivery just acks. A decline is cached too (a declined card must not be
re-attempted). Only a *transient* failure releases the lock and lets Kafka
redeliver — safely, because the PSP key makes the retried charge idempotent.

**Refund/void is the coverage gap.** The charge already had idempotency; the
reversal path did not. A double refund is a direct loss, so `RefundPaymentConsumer`
gets its own key-space and the same three layers. (In the current saga a
post-payment stock-confirm failure is flagged for human review, not auto-refunded,
§3.2 — so this serves the operator-triggered reversal, which a double-click must
not double-execute.)

## Per-service changes

| Service | Change |
|---|---|
| **common-lib** | add `SagaIdempotencyKeys`; add a `commandId` (+ `fingerprint`) field to `ChargePaymentCommand`/`RefundPaymentCommand` in `SagaMessages` (§4.14), stamped from the outbox event id |
| **order-service** | `@Idempotent(required=true)` on checkout; persist the client key into `orders.idempotency_key` (UNIQUE) inside the outbox transaction; catch the constraint → return existing order; `findByIdempotencyKey` lookup |
| **payment-service** | migrate charge to `IdempotencyStore` with the deterministic key; pass the PSP idempotency key; cache result / no-op on redelivery; make refund idempotent; keep the DB constraint |
| gateway, others | nothing |

## Install order

1. common-lib: `SagaIdempotencyKeys` + the `commandId`/`fingerprint` fields on the
   saga commands (both producer and consumer read the same contract — that's why
   it lives in `common-lib`, §4.14).
2. order-service: annotation + constraint handler + `orders.idempotency_key`
   column/migration (Flyway) if not already present.
3. payment-service: swap charge/refund handlers to the shared store + PSP keys.
4. `mvn clean install && docker compose up -d --build`.
5. `./verify-payment-order-idempotency.sh`, then re-run `verify-e2e.sh` (which
   already exercises an idempotency replay) and the resilience/RBAC suites.

## What actually shipped (vs. the design sketch above)

The sketch above was written before implementation; a few things were
resolved differently once real code was involved:

- **`ChargePaymentConsumer.java`/`RefundPaymentConsumer.java`/
  `CheckoutControllerExample.java` were illustrative pseudocode**, not real
  classes (non-`@Component`, commented-out `@KafkaListener`/`@PostMapping`,
  referencing types like `OrderService`/`PaymentRecordRepository` that don't
  exist). They compiled against nothing and briefly broke the build. Deleted
  in favor of real, wired code (below).
- **`PaymentSagaListener` already existed and already had working
  idempotency** (a bespoke DB-check + Redis-lock pair, not the shared
  `IdempotencyStore`). Migrated it to `IdempotencyStore`/`SagaIdempotencyKeys`
  rather than building a parallel consumer.
- **A real self-invocation bug was found and fixed along the way**: the
  charge-processing work now lives in its own bean
  (`PaymentChargeAndRefundTxn`), not inline in the listener - calling
  `@Transactional` code via `this.` within the same class silently skips the
  proxy (same trap as inventory-service's `StockReservationService`/`Txn`
  split), which had been quietly breaking the outbox pattern's atomicity.
- **Refund needed a real trigger**, since nothing in the saga auto-refunds
  (by design - see `OrderSagaOrchestrator#handleStockConfirmationReply`).
  Built an admin-only `POST /api/v1/orders/{orderId}/refund` endpoint, gated
  idempotent at the ORDER level via `OrderSagaState#refundRequestedAt`
  (double-click safe) - separate from `RefundSagaListener`'s own
  `SagaIdempotencyKeys.refund`-keyed dedup, which protects the one command
  that DOES get published from Kafka redelivery. Two key-spaces, same split
  as the charge path.
- **Refund's DB backstop is optimistic locking (`@Version`), not a second
  UNIQUE constraint** - a refund UPDATES an existing `payment_transactions`
  row rather than inserting a new one, so `PaymentTransaction#markRefunded`'s
  "only from SUCCEEDED" guard is only race-safe with `@Version` behind it;
  without it two concurrent refund attempts could both pass the guard before
  either commits.

## Files

```
payment-order-idempotency/
  Readme-payment-order-idempotency.md
  verify-payment-order-idempotency.sh
  common-lib/
    SagaIdempotencyKeys.java                    # deterministic command + PSP keys
  order-service/
    OrderController.java                        # @Idempotent + layer-3 catch + admin refund endpoint
    OrderSagaOrchestrator.java                  # commandId stamping + createOrderAndStartSaga + triggerRefund
    OrderSagaState.java                          # refundRequestedAt (order-level refund idempotency)
  payment-service/
    saga/PaymentSagaListener.java               # outer: Redis lock + DB-race backstop decision layer
    saga/RefundSagaListener.java                # the reversal coverage gap - real, wired
    saga/PaymentChargeAndRefundTxn.java         # inner @Transactional bean (the self-invocation fix)
    saga/PaymentFingerprint.java                # SHA-256 fingerprints for both command types
    entity/PaymentTransaction.java              # REFUNDED status, refund_reference, @Version
```

## What "done" proves

- One client key → exactly one order, one saga (verify A1), **even with Redis
  down** (verify A2 — the backstop path).
- A redelivered charge command → charged once (verify B).
- A repeated refund → refunded once (verify C).

Side effects counted at the source of truth, not inferred from status codes.