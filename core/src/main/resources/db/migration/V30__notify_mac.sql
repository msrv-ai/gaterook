-- K2 (ARCHITECTURE.md §4 "Целостность", §7.4): a linked Slack or Telegram account may approve dev and stage requests
-- from its messages, and a channel's config says where the exact actions go and which workspace and app a click must
-- come from. Both are security rows, so both carry the row MAC of platform.RowMac: a link or a channel edited in
-- gateway.db by anything but the core no longer verifies, and the notifications module ignores it (fail closed) until
-- the person links the account again or saves the channel.
ALTER TABLE notify_link ADD COLUMN mac BLOB;
ALTER TABLE notification_channel ADD COLUMN mac BLOB;

-- Restart reconciliation and the click checks look deliveries up by state and by approval and channel.
CREATE INDEX notify_delivery_state ON notify_delivery (state);
