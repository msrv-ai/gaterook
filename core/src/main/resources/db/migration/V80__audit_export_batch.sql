-- K5 (ARCHITECTURE.md §4 "Ретенция", §6 settings "audit export", range V80–V89).
--
-- pending_to_id: the batch a destination is sending, written down before anything leaves the core. A batch is the
-- agent call rows after last_exported_id up to this id; a stop between the delivery and the cursor sends the very same
-- rows again (the same S3 object, the same BigQuery insert ids, the same syslog ids), so nothing is lost and nothing
-- lands twice under another name. Retention stops at last_exported_id, so the batch's rows stay until it is done.
ALTER TABLE audit_export ADD COLUMN pending_to_id BIGINT;

-- A destination says where the audit goes and with which credentials: a security row. Its MAC (platform.RowMac) covers
-- the kind, name, switch, config, schedule and secret; a row written around the core sends nothing until the person
-- saves it again. The cursor and the last attempt are left out: they change with every batch and decide nothing.
ALTER TABLE audit_export ADD COLUMN mac BLOB;
