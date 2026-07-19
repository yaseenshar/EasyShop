-- V2__create_orders_and_saga_tables.sql
--
-- Three tables working together:
--   orders          - the business entity, what the customer sees
--   order_saga_state - the orchestrator's state machine, crash-recoverable
--   outbox_events   - the transactional outbox (see Step 3.3 rationale)

DROP TABLE IF EXISTS orders CASCADE;

CREATE TABLE orders (
                        id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        user_id         UUID NOT NULL,
                        status          VARCHAR(30) NOT NULL DEFAULT 'PENDING',
                        total_amount    NUMERIC(12, 2) NOT NULL,
                        currency        VARCHAR(3) NOT NULL DEFAULT 'EUR',
                        shipping_address_id UUID,
    -- Idempotency: a client-supplied key (UUID, typically generated client-side
    -- once per checkout attempt) prevents duplicate orders from a double-click
    -- or a retried request after a network timeout. See Phase 4 deep-dive.
                        idempotency_key VARCHAR(255) NOT NULL UNIQUE,
                        created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                        updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

                        CONSTRAINT chk_order_status CHECK (status IN
                                                           ('PENDING', 'RESERVING_STOCK', 'CHARGING_PAYMENT', 'CONFIRMING_STOCK',
                                                            'NOTIFYING', 'CONFIRMED', 'COMPENSATING', 'CANCELLED'))
);

CREATE INDEX idx_orders_user_id ON orders (user_id);
CREATE INDEX idx_orders_idempotency_key ON orders (idempotency_key);