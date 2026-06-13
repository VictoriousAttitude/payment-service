-- DB-level hardening: enforce invariants the application already assumes, so a
-- bug or rogue query cannot violate them behind the code's back.

-- 1. Ledger immutability. The double-entry ledger is append-only by design
--    (no updated_at column). Enforce it: an UPDATE or DELETE on a posted entry
--    is a correctness failure, not a normal operation.
CREATE FUNCTION reject_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries are immutable: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

-- 2. Timezone-aware timestamps. Plain TIMESTAMP stores a wall-clock with no
--    zone; the same instant read back under a different server zone shifts.
--    A payments ledger must pin absolute instants, so use TIMESTAMPTZ. Existing
--    rows were written by now()/Instant as UTC, so reinterpret them as UTC.
ALTER TABLE merchants
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'UTC';

ALTER TABLE transactions
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'UTC';

ALTER TABLE ledger_entries
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC';

ALTER TABLE outbox_events
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'UTC',
    ALTER COLUMN dispatched_at TYPE TIMESTAMPTZ USING dispatched_at AT TIME ZONE 'UTC';

-- 3. Constrain status to the known state set. Guards against a typo or schema
--    drift writing a status the in-memory state machine has never heard of.
ALTER TABLE transactions ADD CONSTRAINT chk_transactions_status
    CHECK (status IN ('CREATED', 'PENDING', 'AUTHORIZED', 'CAPTURED', 'SETTLED', 'FAILED', 'REFUNDED'));

-- 4. Partial index for the reconciliation stuck-transaction sweep, which scans
--    non-terminal rows by age. The terminal rows (the vast majority over time)
--    are excluded, keeping the index small and the scan hot. Replaces the
--    low-selectivity full-status index.
DROP INDEX idx_transactions_status;
CREATE INDEX idx_transactions_active ON transactions(created_at)
    WHERE status IN ('CREATED', 'PENDING', 'AUTHORIZED');
