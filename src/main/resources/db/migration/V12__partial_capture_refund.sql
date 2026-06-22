-- Partial / multi-capture and partial refund.

-- 1. Two new lifecycle labels: PARTIALLY_CAPTURED (captured < authorized amount)
--    and PARTIALLY_REFUNDED (refunded < captured amount). Recreate the status
--    CHECK so the DB still rejects any status the state machine never emits.
ALTER TABLE transactions DROP CONSTRAINT chk_transactions_status;
ALTER TABLE transactions ADD CONSTRAINT chk_transactions_status
    CHECK (status IN (
        'CREATED', 'PENDING', 'AUTHORIZED', 'PARTIALLY_CAPTURED', 'CAPTURED',
        'SETTLED', 'FAILED', 'PARTIALLY_REFUNDED', 'REFUNDED'
    ));

-- 2. Per-operation log. A transaction now has many captures and many refunds.
--    Each operation is recorded with its own optional idempotency key so a
--    retried capture/refund replays instead of double-applying. The unique
--    constraint is the gate; Postgres treats NULL keys as distinct, so an
--    un-keyed operation never collides (no dedup is intended for those).
CREATE TABLE payment_operations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transactions(id),
    type VARCHAR(16) NOT NULL CHECK (type IN ('CAPTURE', 'REFUND')),
    amount BIGINT NOT NULL CHECK (amount > 0),
    idempotency_key VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_operations_idem UNIQUE (transaction_id, idempotency_key)
);

CREATE INDEX idx_payment_operations_txn ON payment_operations(transaction_id);
