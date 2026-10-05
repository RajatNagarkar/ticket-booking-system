-- One-time setup of the application role on a managed database (Neon).
-- The compose equivalent is docker/postgres/init.sql.
--
-- Run as the schema owner (the role Flyway migrates as, e.g. neondb_owner):
--   psql "$NEON_OWNER_URL" -v app_password="$APP_DB_PASSWORD" -f scripts/neon-bootstrap.sql
--
-- Neon rejects weak passwords for roles created in SQL; use e.g. `openssl rand -base64 24`.
\set ON_ERROR_STOP on

CREATE ROLE app_runner LOGIN PASSWORD :'app_password';
GRANT USAGE ON SCHEMA public TO app_runner;

-- Tables Flyway creates later (default privileges apply to objects created by the current role).
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_runner;

-- Tables that already exist, in case migrations ran before this script.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO app_runner;
