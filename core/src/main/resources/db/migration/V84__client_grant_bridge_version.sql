-- The bridge version a grant's last token request reported, so the app can tell a Claude Desktop extension that still
-- carries an older gw-bridge.mjs. Informational like last_pid: the row MAC leaves it out, so existing rows still verify.
ALTER TABLE client_grant ADD COLUMN bridge_version VARCHAR(32);
