-- See order-service's V4 for the full rationale: the outbox severs the
-- thread-local trace scope, so the traceparent has to be persisted with the
-- event and restored when it is published. Nullable - a missing context means
-- publish anyway and start a new trace, never drop the event.
ALTER TABLE outbox_events ADD COLUMN trace_parent VARCHAR(55);
