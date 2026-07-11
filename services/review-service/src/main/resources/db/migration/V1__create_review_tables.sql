-- V1__create_review_tables.sql (MySQL dialect)
-- BINARY(16) UUIDs per the Phase 5 lesson (Hibernate default mapping on MySQL).

CREATE TABLE reviews (
                         id                BINARY(16) NOT NULL PRIMARY KEY,
                         product_id        BINARY(16) NOT NULL,
                         user_id           BINARY(16) NOT NULL,
                         rating            TINYINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
                         title             VARCHAR(150) NOT NULL,
                         body              TEXT,
                         status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                         verified_purchase BOOLEAN NOT NULL DEFAULT false,
                         created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                         moderated_at      TIMESTAMP NULL,

                         CONSTRAINT chk_review_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),

    -- One review per user per product, enforced at the DB level. The
    -- service layer checks first for a friendly error message, but the
    -- constraint is what makes the invariant actually hold under
    -- concurrent double-submits - same defense-in-depth stance as
    -- payment-service's idempotency_key unique index.
                         CONSTRAINT uq_one_review_per_user_product UNIQUE (user_id, product_id)
) ENGINE=InnoDB;

-- The hot public read path: approved reviews for a product, newest first.
CREATE INDEX idx_reviews_product_status_created
    ON reviews (product_id, status, created_at DESC);

-- Moderation queue read path: all pending, oldest first.
CREATE INDEX idx_reviews_status_created ON reviews (status, created_at);