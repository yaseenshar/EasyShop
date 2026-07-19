-- V1__create_catalog_tables.sql (MySQL dialect)
--
-- BINARY(16) for all UUID columns - applying the lesson from Phase 5's
-- inventory schema (Hibernate's default UUID mapping on MySQL is 16-byte
-- binary, and validate-mode startup fails on a CHAR(36) mismatch).

CREATE TABLE categories (
                            id          BINARY(16) NOT NULL PRIMARY KEY,
                            name        VARCHAR(100) NOT NULL UNIQUE,
                            slug        VARCHAR(120) NOT NULL UNIQUE,
                            parent_id   BINARY(16) NULL,
                            created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                            CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES categories(id)
) ENGINE=InnoDB;

CREATE TABLE products (
                          id            BINARY(16) NOT NULL PRIMARY KEY,
                          sku           VARCHAR(64) NOT NULL UNIQUE,
                          name          VARCHAR(255) NOT NULL,
                          description   TEXT,
                          price         DECIMAL(12, 2) NOT NULL CHECK (price >= 0),
                          currency      CHAR(3) NOT NULL DEFAULT 'USD',
                          category_id   BINARY(16) NOT NULL,
                          active        BOOLEAN NOT NULL DEFAULT true,
                          created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                              ON UPDATE CURRENT_TIMESTAMP,

                          CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES categories(id)
) ENGINE=InnoDB;

-- The two hottest read paths get covering indexes:
-- product detail by SKU (external links, deep links) and listing by category.
CREATE INDEX idx_products_sku ON products (sku);
CREATE INDEX idx_products_category_active ON products (category_id, active);