-- What an AI app sent with a call: the UTF-8 bytes of its arguments as canonical JSON, before masking. Written on
-- the call's received row only, next to the arguments; result_bytes on the completed row is what went back to the
-- app. Activity shows both per call and Overview sums them by day. NULL on the other rows and on rows written before
-- this column existed; the hash chain covers it only where it is set, so those rows keep their hashes.
ALTER TABLE tool_call ADD COLUMN request_bytes BIGINT;
