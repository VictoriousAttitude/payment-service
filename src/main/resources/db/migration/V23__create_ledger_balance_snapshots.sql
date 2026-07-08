-- O(1) balance reads via materialized checkpoints (the Stripe ledger pattern).
--
-- Every balance query so far is a full SUM over ledger_entries: unbounded, it
-- grows with the ledger forever. These two tables cache a rolling checkpoint so
-- a balance read costs snapshot + SUM(entries after the checkpoint) instead of
-- SUM(all history). The checkpoint is DERIVED and rebuildable: it is not part
-- of the immutable ledger (no append-only trigger), and reconciliation
-- re-derives the exact balance from ledger_entries and flags any drift.
--
-- Correctness rests on the same commit-visibility safety window epoch anchoring
-- uses: the snapshotter only folds entries older than lag-seconds, so every
-- entry at or before the cursor is durably committed before it is summed. New
-- entries always carry created_at = now() > cursor, so they land in the live
-- delta, never in the gap between snapshot and delta.

CREATE TABLE ledger_balance_snapshots (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_type  VARCHAR(20) NOT NULL,
    account_id    UUID        NOT NULL,
    currency      VARCHAR(3)  NOT NULL,
    total_debits  BIGINT      NOT NULL DEFAULT 0 CHECK (total_debits >= 0),
    total_credits BIGINT      NOT NULL DEFAULT 0 CHECK (total_credits >= 0),
    UNIQUE (account_type, account_id, currency)
);

-- Single-row cursor: the instant up to which the snapshots are folded. A live
-- balance sums only entries created after this, so read cost is bounded by the
-- snapshot cadence, not by total ledger size. Seeded at the epoch so that
-- before the first fold every read degenerates to the full SUM (exact, just
-- unaccelerated) - the acceleration is purely additive and never a source of
-- truth on its own.
CREATE TABLE ledger_snapshot_cursor (
    id    SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    as_of TIMESTAMPTZ NOT NULL
);
INSERT INTO ledger_snapshot_cursor (id, as_of) VALUES (1, 'epoch');

-- The snapshotter aggregates a created_at window each run and the delta read
-- filters on created_at; without this index both fall back to a sequential scan.
CREATE INDEX idx_ledger_entries_created_at ON ledger_entries(created_at);
