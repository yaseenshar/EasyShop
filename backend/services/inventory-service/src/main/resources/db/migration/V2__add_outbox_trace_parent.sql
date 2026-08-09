-- MySQL side of the polyglot split; same change as order-service V4 and
-- payment-service V3. See order-service's V4 for why the traceparent must be
-- persisted with the row rather than inferred at publish time.
ALTER TABLE outbox_events ADD COLUMN trace_parent VARCHAR(55) NULL;
