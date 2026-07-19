-- V1__create_payment_transactions_table.sql
--
-- Note: idempotency_key has a UNIQUE constraint here too, as a SECOND layer
-- of defense behind the Redis lock. Redis is fast but is not the system of
-- record - if Redis were ever flushed, restarted, or misconfigured, this
-- DB-level constraint is the backstop that makes a true double-charge
-- structurally impossible, not just unlikely. This "defense in depth" framing
-- (cache for speed, DB constraint for correctness guarantee) is a strong
-- thing to articulate explicitly in an interview.

CREATE TABLE payment_transactions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id            UUID NOT NULL,
    user_id             UUID NOT NULL,
    amount              NUMERIC(12, 2) NOT NULL,
    currency            VARCHAR(3) NOT NULL,
    idempotency_key     VARCHAR(255) NOT NULL UNIQUE,
    status              VARCHAR(20) NOT NULL,
    gateway_reference   VARCHAR(255),       -- e.g. Stripe PaymentIntent ID
    failure_reason      TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_payment_status CHECK (status IN ('SUCCEEDED', 'FAILED', 'PROCESSING'))
);

CREATE INDEX idx_payment_transactions_order_id ON payment_transactions (order_id);
CREATE UNIQUE INDEX idx_payment_transactions_idempotency_key ON payment_transactions (idempotency_key);

CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(50) NOT NULL,
    aggregate_id    UUID NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    topic           VARCHAR(100) NOT NULL,
    payload         JSONB NOT NULL,
    published       BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at) WHERE published = false;