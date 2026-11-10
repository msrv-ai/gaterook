-- A pending version that a rotation at the source wrote without getting a clear answer (no answer in time, the
-- connection dropped while the change ran): the source may already use it, so it is kept instead of destroyed until
-- the next Rotate finds out which value the source accepts. NULL for every other version.
ALTER TABLE secret_version ADD COLUMN unconfirmed_at TIMESTAMP;
