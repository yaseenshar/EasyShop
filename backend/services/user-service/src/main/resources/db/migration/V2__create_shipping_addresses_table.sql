DROP TABLE IF EXISTS users CASCADE;

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

DROP TABLE IF EXISTS shipping_addresses CASCADE;

CREATE TABLE shipping_addresses
(
    id             UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_id        UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    label          VARCHAR(50) NOT NULL,
    line1          VARCHAR(255) NOT NULL,
    line2          VARCHAR(255),
    city           VARCHAR(100) NOT NULL,
    state_province VARCHAR(100),
    postal_code    VARCHAR(20) NOT NULL,
    country_code   VARCHAR(20) NOT NULL, -- ISO 3166-1 alpha-2
    is_default     BOOLEAN     NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_shipping_addresses_user_id ON shipping_addresses (user_id);

-- Ensures only one default address per user at the DB level
CREATE UNIQUE INDEX uq_one_default_address_per_user
    ON shipping_addresses (user_id) WHERE is_default = true;
