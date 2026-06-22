-- Chargebacks raised against captured payments. A dispute is its own aggregate
-- with an independent lifecycle, so it lives in its own table rather than as a
-- transaction status. The money movement on a lost dispute is posted to
-- ledger_entries (append-only); this table holds only the dispute lifecycle.
CREATE TABLE disputes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transactions(id),
    reason VARCHAR(30) NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    provider_reference VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_disputes_status
        CHECK (status IN ('OPEN', 'UNDER_REVIEW', 'WON', 'LOST'))
);

-- Disputes are read and existence-checked by transaction.
CREATE INDEX idx_disputes_txn ON disputes(transaction_id);

-- Enforce at most one live (non-terminal) dispute per transaction at the DB
-- level, not just in service code: a partial unique index over the open states.
CREATE UNIQUE INDEX idx_disputes_one_live ON disputes(transaction_id)
    WHERE status IN ('OPEN', 'UNDER_REVIEW');
