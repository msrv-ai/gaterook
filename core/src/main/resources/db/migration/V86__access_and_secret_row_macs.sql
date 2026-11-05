-- ARCHITECTURE.md §4 "Persistence and integrity": security rows that had no row MAC get one. An access token, an OAuth
-- sign-in and its tokens decide who reaches an endpoint, and a secret row decides whose config a stored value is
-- decrypted for. A row edited or inserted in gateway.db by anything but the core no longer verifies, and is refused.
-- Rows written by an older version are signed once, by the start that applies this migration (LegacyRowSigning).
ALTER TABLE api_token ADD COLUMN mac BLOB;
ALTER TABLE secret ADD COLUMN mac BLOB;
ALTER TABLE oauth_grant ADD COLUMN mac BLOB;
ALTER TABLE oauth_token ADD COLUMN mac BLOB;
