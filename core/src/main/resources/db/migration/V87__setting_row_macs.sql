-- ARCHITECTURE.md §4 "Persistence and integrity": the settings that can do harm by themselves get the row MAC of
-- platform.RowMac. The audit retention (key audit) decides which history the retention job deletes; the catalog's last
-- good index (catalog.last-good) and the highest sequence the core accepted (catalog.sequence) decide what counts as a
-- rollback. Every write by the core signs them (SettingStore); a row edited, replaced or deleted in gateway.db by
-- anything but the core no longer verifies, and its owner refuses its effect until the person saves it again or a
-- fresh signed catalog arrives. The audit row exists from this migration on, so deleting it is an edit too. Rows
-- written by an older version are signed once, by the start that applies this migration (LegacyRowSigning).
ALTER TABLE setting ADD COLUMN mac BLOB;
