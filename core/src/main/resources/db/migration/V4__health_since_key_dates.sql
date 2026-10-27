-- M1 range, step API (the fields the finished pages still missed, ARCHITECTURE.md §6).
--
-- health_since: when the connection's current health state began ("Connection refused since 11:42"). Like the other
-- health columns it is written without a new version and outside the row's MAC: health decides nothing.
ALTER TABLE connection ADD COLUMN health_since TIMESTAMP;

-- When the master key in use was made and when it was last rotated (Secrets: "Created Jul 14, never rotated"). Facts
-- for the card, not trust: a key that exists without them (made before this migration) shows no creation date.
ALTER TABLE master_key_state ADD COLUMN key_created_at TIMESTAMP;
ALTER TABLE master_key_state ADD COLUMN key_rotated_at TIMESTAMP;
