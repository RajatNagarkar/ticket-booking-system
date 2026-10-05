-- Flyway callback, run on every startup before migrations, as the schema owner (FLYWAY_USER).
-- Creates the application role (DB_USERNAME / DB_PASSWORD) if missing, keeps its password in sync,
-- and grants it row access only, so a fresh managed database needs no manual setup.
-- Skipped when the app connects as the owner itself (tests, single-role setups).
--
-- Flyway does not replace placeholders inside dollar-quoted text, so the values are passed into
-- the DO block as settings on the (migration-only) session. DB_PASSWORD must not contain a single quote.
SELECT set_config('tbs.app_user', '${app_user}', false),
       set_config('tbs.app_password', '${app_password}', false);

DO $do$
DECLARE
    app_user     text := current_setting('tbs.app_user');
    app_password text := current_setting('tbs.app_password');
BEGIN
    IF app_user = '' OR app_user = current_user THEN
        RETURN;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app_user) THEN
        BEGIN
            EXECUTE format('CREATE ROLE %I LOGIN', app_user);
        EXCEPTION WHEN duplicate_object THEN
            NULL; -- another instance created it concurrently
        END;
    END IF;
    EXECUTE format('ALTER ROLE %I WITH LOGIN PASSWORD %L', app_user, app_password);

    EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', app_user);
    EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', app_user);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I', app_user);
    -- Migration history belongs to Flyway alone.
    EXECUTE format('REVOKE ALL ON flyway_schema_history FROM %I', app_user);
END
$do$;
