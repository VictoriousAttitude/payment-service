-- Idempotency payload fingerprint: a key replayed with a different payload is
-- a client bug, not a retry. Without this, the original transaction is silently
-- returned for a request with a different amount.
-- Empty string marks rows created before fingerprinting (check is skipped).
ALTER TABLE transactions ADD COLUMN request_hash VARCHAR(64) NOT NULL DEFAULT '';
