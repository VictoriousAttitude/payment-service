-- Rolling reserve holds withheld at settlement. Each settlement split may
-- withhold a slice of the merchant's net into MERCHANT_RESERVE; this table is
-- the lifecycle row for that slice (the money itself lives in ledger_entries).
CREATE TABLE reserve_holds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transactions(id),
    merchant_id UUID NOT NULL REFERENCES merchants(id),
    amount BIGINT NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('HELD', 'RELEASED')),
    release_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

-- DB backstop for the settle() idempotency guard: a transaction settles once,
-- so it can carry at most one reserve hold.
CREATE UNIQUE INDEX uq_reserve_holds_txn ON reserve_holds(transaction_id);

-- The release batch scans HELD rows past their release time; RELEASED rows
-- (the vast majority over time) stay out of the index.
CREATE INDEX idx_reserve_holds_due ON reserve_holds(release_at) WHERE status = 'HELD';

-- Payout batches disbursing the merchant's payable balance. Created here with
-- the reserve schema (one payout/reserve DDL unit); the entity and service
-- arrive with the payout feature - Hibernate validate ignores unmapped tables.
CREATE TABLE payouts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL REFERENCES merchants(id),
    amount BIGINT NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'PAID', 'FAILED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_payouts_merchant ON payouts(merchant_id, created_at);

-- The confirm batch scans PENDING payouts past the confirmation delay.
CREATE INDEX idx_payouts_confirmable ON payouts(created_at) WHERE status = 'PENDING';
