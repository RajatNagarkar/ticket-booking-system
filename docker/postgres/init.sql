-- Runs once, when the compose database volume is first created.
-- Mirrors production: Flyway migrates as the schema owner (tbs_owner, the POSTGRES_USER),
-- while the application connects as app_runner, which can only read and write rows.
CREATE ROLE app_runner LOGIN PASSWORD 'app_runner';
GRANT USAGE ON SCHEMA public TO app_runner;
ALTER DEFAULT PRIVILEGES FOR ROLE tbs_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_runner;
