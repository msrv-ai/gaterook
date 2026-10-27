-- K4 review 2026-10-27 (plugins' range V50–V59): the SHA-256 of the version kept for Roll back. A roll back loaded
-- whatever plugins/<id>/<previous>/plugin.jar held; now it must be the file that was installed, as every other load
-- is checked against the database (ARCHITECTURE.md §7.5, report 11 §6.3). Signed with the row (platform.RowMac).
ALTER TABLE plugin ADD COLUMN previous_sha256 VARCHAR(64);
