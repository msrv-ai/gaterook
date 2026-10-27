-- M1.4 (review round 3): the template row decides what runs where (Runs on, current version, status), so it is signed
-- like every other security-relevant row (ARCHITECTURE.md §4). Versions keep their own MAC; they are append-only and
-- can't be signed again, so the binding to a connection lives here, where the core signs a detach too.
ALTER TABLE template ADD COLUMN mac BLOB;
