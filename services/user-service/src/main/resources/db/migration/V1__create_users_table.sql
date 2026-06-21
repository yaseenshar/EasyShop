-- V1__create_users_table.sql
--
-- Design notes:
-- 1. keycloak_id is the JWT 'sub' claim - the link between Keycloak identity
--    and our business-domain user record. It is NOT the primary key directly
--    exposed to clients (we use a separate internal UUID 'id') so that we can
--    decouple our internal references from the identity provider if it ever changes.
-- 2. We do NOT store passwords, password hashes, or any credential material here.
--    That is Keycloak's exclusive responsibility. This is the single most important
--    design decision in this table - it eliminates an entire class of breach risk.
-- 3. created_at/updated_at use TIMESTAMPTZ, not TIMESTAMP, to avoid timezone bugs
--    that are notoriously hard to debug in production (classic interview gotcha).

CREATE TABLE users
(
    id           UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    keycloak_id  VARCHAR(255) NOT NULL UNIQUE,
    email        VARCHAR(320) NOT NULL UNIQUE,
    first_name   VARCHAR(100) NOT NULL,
    last_name    VARCHAR(100) NOT NULL,
    phone_number VARCHAR(20),
    loyalty_tier VARCHAR(20)  NOT NULL DEFAULT 'BRONZE',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT chk_loyalty_tier CHECK (loyalty_tier IN ('BRONZE', 'SILVER', 'GOLD', 'PLATINUM'))
);

-- Index on keycloak_id: every authenticated request looks up the user by this
-- column (extracted from JWT 'sub' claim), so this is the hottest read path.
CREATE INDEX idx_users_keycloak_id ON users (keycloak_id);