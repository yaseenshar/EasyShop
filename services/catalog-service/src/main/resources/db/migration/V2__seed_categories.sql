-- V2__seed_categories.sql (MySQL dialect)
--
-- Fixed taxonomy for this design (no admin CRUD for categories - see
-- CategoryController). UUID() gives a compact way to generate the BINARY(16)
-- primary key from SQL; UUID_TO_BIN keeps the on-disk representation
-- consistent with what Hibernate writes for every other UUID column here.

INSERT INTO categories (id, name, slug) VALUES
    (UUID_TO_BIN(UUID()), 'Electronics', 'electronics'),
    (UUID_TO_BIN(UUID()), 'Apparel', 'apparel'),
    (UUID_TO_BIN(UUID()), 'Home & Kitchen', 'home-kitchen'),
    (UUID_TO_BIN(UUID()), 'Beauty', 'beauty');
