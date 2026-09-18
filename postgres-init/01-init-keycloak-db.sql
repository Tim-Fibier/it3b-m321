-- Wird beim ersten Start des Postgres-Containers automatisch ausgeführt
-- (docker-entrypoint-initdb.d). Legt die separate Datenbank für Keycloak an,
-- da Keycloak nicht das chat_db-Schema mitbenutzen soll.

CREATE USER keycloak_user WITH PASSWORD 'keycloak_password';
CREATE DATABASE keycloak OWNER keycloak_user;
GRANT ALL PRIVILEGES ON DATABASE keycloak TO keycloak_user;
