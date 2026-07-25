-- V2__add_refund_support.sql
--
-- payment-order-idempotency ticket: the refund/void path is the coverage gap
-- the charge already had. refund_reference mirrors gateway_reference (the
-- PSP's transaction id for the reversal); version backs optimistic locking so
-- markRefunded()'s "only from SUCCEEDED" guard is a genuine race-safe DB
-- backstop, not just an in-app check two concurrent refund attempts could
-- both slip past (see PaymentTransaction#version javadoc).

ALTER TABLE payment_transactions
    ADD COLUMN refund_reference VARCHAR(255),
    ADD COLUMN version INTEGER NOT NULL DEFAULT 0;

ALTER TABLE payment_transactions
    DROP CONSTRAINT chk_payment_status;

ALTER TABLE payment_transactions
    ADD CONSTRAINT chk_payment_status CHECK (status IN ('SUCCEEDED', 'FAILED', 'PROCESSING', 'REFUNDED'));
