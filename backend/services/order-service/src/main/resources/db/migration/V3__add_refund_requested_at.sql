-- V3__add_refund_requested_at.sql
--
-- Order-level idempotency gate for the admin refund endpoint (payment-order-
-- idempotency ticket). NULL until the first accepted refund request; a
-- second call against an already-set row is a no-op at the controller level
-- instead of publishing a second RefundPaymentCommand. Payment-service's own
-- commandId-keyed dedup (SagaIdempotencyKeys.refund) is the SEPARATE guarantee
-- that protects the one command that does get published from Kafka redelivery.

ALTER TABLE order_saga_state
    ADD COLUMN refund_requested_at TIMESTAMPTZ;
