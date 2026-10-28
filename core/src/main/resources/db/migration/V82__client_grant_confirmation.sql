-- Keep production access approval with the grant. Existing grants require one approval after upgrading;
-- token activity does not establish consent and must not be used to backfill this field.
ALTER TABLE client_grant ADD COLUMN confirmed_at TIMESTAMP;
