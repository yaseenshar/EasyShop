-- V1__create_inventory_tables.sql (MySQL dialect)
--
-- Key difference from the Postgres migrations you've seen: MySQL uses
-- AUTO_INCREMENT/CHAR(36) patterns differently than Postgres's gen_random_uuid().
-- We use CHAR(36) + application-generated UUIDs (via Hibernate) rather than
-- relying on a MySQL-native UUID function, for portability and consistency
-- with how every other service generates IDs.

CREATE TABLE product_stock (
                               id              BINARY(16) NOT NULL PRIMARY KEY,
                               product_id      BINARY(16) NOT NULL UNIQUE,
                               available_qty   INT NOT NULL CHECK (available_qty >= 0),
                               reserved_qty    INT NOT NULL DEFAULT 0 CHECK (reserved_qty >= 0),
                               version         INT NOT NULL DEFAULT 0,
                               updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                                   ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- NOTE on BINARY(16) vs CHAR(36): Hibernate 7's default UUID-to-column
-- mapping strategy on MySQL stores UUIDs as a 16-byte binary value (not a
-- 36-character string) unless you explicitly override the JDBC type with
-- @JdbcTypeCode(SqlTypes.CHAR) or similar on each @Id field. BINARY(16) is
-- more storage-efficient (16 bytes vs 36) and is the path of least surprise
-- against Hibernate's default behavior in validate mode - the same kind of
-- entity/schema mismatch that just broke payment-service's outbox table
-- (Phase 4) would happen again here if this guessed wrong, so this was
-- checked deliberately rather than assumed.

-- InnoDB (MySQL's default engine) is required here, not MyISAM - MyISAM
-- doesn't support row-level locking or transactions at all, which would
-- silently break both our optimistic locking (@Version) AND basic ACID
-- guarantees. This is exactly the kind of MySQL-specific gotcha worth
-- knowing cold for an interview.

CREATE INDEX idx_product_stock_product_id ON product_stock (product_id);

CREATE TABLE stock_reservations (
                                    id              BINARY(16) NOT NULL PRIMARY KEY,
    -- order_id is intentionally NOT unique: a multi-item order produces
    -- one reservation row PER LINE ITEM, all sharing this order_id. The
    -- original UNIQUE constraint here assumed one-reservation-per-order,
    -- which was wrong - caught and corrected alongside the matching
    -- repository fix (findByOrderId -> findAllByOrderId).
                                    order_id        BINARY(16) NOT NULL,
                                    product_id      BINARY(16) NOT NULL,
                                    quantity        INT NOT NULL,
                                    status          VARCHAR(20) NOT NULL,
                                    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                    CONSTRAINT chk_reservation_status CHECK (status IN ('RESERVED', 'CONFIRMED', 'RELEASED'))
) ENGINE=InnoDB;

CREATE INDEX idx_stock_reservations_order_id ON stock_reservations (order_id);

CREATE TABLE outbox_events (
                               id              BINARY(16) NOT NULL PRIMARY KEY,
                               aggregate_type  VARCHAR(50) NOT NULL,
                               aggregate_id    BINARY(16) NOT NULL,
                               event_type      VARCHAR(100) NOT NULL,
                               topic           VARCHAR(100) NOT NULL,
                               payload         JSON NOT NULL,
                               published       BOOLEAN NOT NULL DEFAULT false,
                               created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                               published_at    TIMESTAMP NULL
) ENGINE=InnoDB;

CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at, published);