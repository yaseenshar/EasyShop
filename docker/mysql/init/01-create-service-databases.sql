-- docker/postgres/init/01-create-service-databases.sql
--
-- Runs ONCE, automatically, when the postgres container's data volume is
-- first initialized (Docker's postgres image executes every .sql/.sh file
-- in /docker-entrypoint-initdb.d on first boot only - re-running
-- docker compose up afterward will NOT re-trigger this).
--
-- This is the actual fix for the "relation already exists" error: each
-- service gets its OWN database (not just its own table prefix) so that
-- order-service's outbox_events and payment-service's outbox_events are
-- two completely separate tables in two separate databases, with zero
-- possibility of name collision - exactly the "each service owns its own
-- database" principle from Phase 1, now actually enforced rather than just
-- described.
--
-- Note: 'easyshop' (the original single DB referenced in early examples)
-- is being retired in favor of these per-service databases. user-service
-- and order-service configs need their datasource URL updated too (see
-- instructions below the script).

CREATE DATABASE IF NOT EXISTS easyshop_inventory;
-- catalog-service and cart-service intentionally excluded: catalog uses
-- MySQL (Phase 1 decision), cart-service uses Redis as its primary store.

GRANT ALL PRIVILEGES ON easyshop_inventory.* TO 'easyshop'@'%';
FLUSH PRIVILEGES;