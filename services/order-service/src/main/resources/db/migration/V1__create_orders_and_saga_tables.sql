-- V1__create_orders_and_saga_tables.sql
--
-- Three tables working together:
--   orders          - the business entity, what the customer sees
--   order_saga_state - the orchestrator's state machine, crash-recoverable
--   outbox_events   - the transactional outbox (see Step 3.3 rationale)

CREATE TABLE orders (
                        id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        user_id         UUID NOT NULL,
                        status          VARCHAR(30) NOT NULL DEFAULT 'PENDING',
                        total_amount    NUMERIC(12, 2) NOT NULL,
                        currency        CHAR(3) NOT NULL DEFAULT 'EUR',
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

CREATE TABLE order_items (
                             id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                             order_id        UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
                             product_id      UUID NOT NULL,
                             quantity        INTEGER NOT NULL CHECK (quantity > 0),
                             unit_price      NUMERIC(12, 2) NOT NULL CHECK (unit_price >= 0)
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);

-- The saga's own state machine record. Separate from `orders.status` (which is
-- the customer-facing summary) because the saga needs additional bookkeeping:
-- which compensations have already run, correlation IDs for in-flight async
-- replies, and a version column for optimistic locking against concurrent
-- reply handlers.
CREATE TABLE order_saga_state (
                                  order_id            UUID PRIMARY KEY REFERENCES orders(id) ON DELETE CASCADE,
                                  current_step        VARCHAR(30) NOT NULL,
                                  reservation_id      UUID,             -- set once inventory confirms reservation
                                  payment_transaction_id UUID,          -- set once payment confirms charge
                                  compensations_run   TEXT[] NOT NULL DEFAULT '{}',
                                  version             INTEGER NOT NULL DEFAULT 0,  -- optimistic locking column
                                  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The Transactional Outbox table. Written in the SAME local transaction as
-- any orders/order_saga_state mutation. A separate publisher process polls
-- (or, later, Debezium CDC-streams) unpublished rows to Kafka.
CREATE TABLE outbox_events (
                               id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                               aggregate_type  VARCHAR(50) NOT NULL,    -- e.g. 'Order'
                               aggregate_id    UUID NOT NULL,            -- e.g. the order_id
                               event_type      VARCHAR(100) NOT NULL,    -- e.g. 'OrderCreatedEvent'
                               topic           VARCHAR(100) NOT NULL,    -- target Kafka topic
                               payload         JSONB NOT NULL,           -- serialized event body
                               published       BOOLEAN NOT NULL DEFAULT false,
                               created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                               published_at    TIMESTAMPTZ
);

-- The publisher's hot query is "give me unpublished rows in creation order" -
-- this partial index keeps that query fast even as the table accumulates
-- millions of published (and eventually archived/deleted) rows.
CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at)
    WHERE published = false;