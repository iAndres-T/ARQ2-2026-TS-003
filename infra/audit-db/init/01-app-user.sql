-- Runs once on an empty audit-db volume, as the owner (POSTGRES_USER). Flyway later creates the tables as the owner.
-- The service runs as audit_app, which can only INSERT and SELECT. Password: env AUDIT_APP_PASSWORD (dev default below).
\getenv app_pw AUDIT_APP_PASSWORD
\if :{?app_pw}
\else
\set app_pw audit_app
\endif
CREATE ROLE audit_app LOGIN PASSWORD :'app_pw';
ALTER DEFAULT PRIVILEGES GRANT SELECT, INSERT ON TABLES TO audit_app;
