-- Store only the SHA-256 of each API key, never the plaintext. A leaked DB dump
-- then yields no usable credentials. Lookup stays O(1): the auth path hashes the
-- presented key and probes the unique hash index. pgcrypto's digest() backfills
-- existing rows to the same lowercase-hex encoding the application produces.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

ALTER TABLE merchants ADD COLUMN api_key_hash VARCHAR(64);

UPDATE merchants
SET api_key_hash = encode(digest(api_key, 'sha256'), 'hex');

ALTER TABLE merchants ALTER COLUMN api_key_hash SET NOT NULL;
ALTER TABLE merchants ADD CONSTRAINT uq_merchants_api_key_hash UNIQUE (api_key_hash);

-- Drop the plaintext column (and its unique constraint) so no key survives at rest.
ALTER TABLE merchants DROP COLUMN api_key;
