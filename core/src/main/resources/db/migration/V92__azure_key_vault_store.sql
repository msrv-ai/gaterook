-- Azure Key Vault joins the external stores as kind 'azure-kv'. SQLite can't change a CHECK constraint in place, so
-- external_store is rebuilt with every row, id, optimistic version and signed field unchanged (the store MACs in
-- secrets.store-macs cover kind, name, config and auth_secret_id, none of which changes).
--
-- secret.store_id and secret_lease.store_id reference external_store (id), and the core enforces foreign keys. While
-- the table is dropped those rows have no parent, so the check waits for the commit (defer_foreign_keys lasts for this
-- transaction only). The rows go back into a table named external_store, not into a copy that is renamed afterwards:
-- inserting the parents under the referenced name is what settles the violations the drop counted, and a rename
-- would leave them counted and fail the commit.
PRAGMA defer_foreign_keys = ON;

CREATE TABLE external_store_copy (
  id              VARCHAR(36) NOT NULL PRIMARY KEY,
  kind            VARCHAR(8)  NOT NULL,
  name            VARCHAR(64) NOT NULL,
  config          CLOB        NOT NULL,
  auth_secret_id  VARCHAR(36),
  state           VARCHAR(16) NOT NULL,
  last_checked_at TIMESTAMP,
  created_at      TIMESTAMP   NOT NULL,
  version         BIGINT      NOT NULL
);
INSERT INTO external_store_copy (id, kind, name, config, auth_secret_id, state, last_checked_at, created_at, version)
SELECT id, kind, name, config, auth_secret_id, state, last_checked_at, created_at, version
FROM external_store;

DROP TABLE external_store;

CREATE TABLE external_store (
  id              VARCHAR(36) NOT NULL PRIMARY KEY,
  kind            VARCHAR(8)  NOT NULL CHECK (kind IN ('vault', 'gcp-sm', 'aws-sm', 'azure-kv')),
  name            VARCHAR(64) NOT NULL UNIQUE,
  config          CLOB        NOT NULL,
  auth_secret_id  VARCHAR(36),
  state           VARCHAR(16) NOT NULL,
  last_checked_at TIMESTAMP,
  created_at      TIMESTAMP   NOT NULL,
  version         BIGINT      NOT NULL
);
INSERT INTO external_store (id, kind, name, config, auth_secret_id, state, last_checked_at, created_at, version)
SELECT id, kind, name, config, auth_secret_id, state, last_checked_at, created_at, version
FROM external_store_copy;

DROP TABLE external_store_copy;
