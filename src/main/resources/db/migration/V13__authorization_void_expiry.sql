-- Authorization void and expiry.

-- 1. Two terminal labels for an authorization that is never captured: VOIDED
--    (merchant cancels the hold) and EXPIRED (the time-bounded hold lapses).
--    Recreate the status CHECK so the DB keeps rejecting any unknown status.
ALTER TABLE transactions DROP CONSTRAINT chk_transactions_status;
ALTER TABLE transactions ADD CONSTRAINT chk_transactions_status
    CHECK (status IN (
        'CREATED', 'PENDING', 'AUTHORIZED', 'PARTIALLY_CAPTURED', 'CAPTURED',
        'SETTLED', 'FAILED', 'PARTIALLY_REFUNDED', 'REFUNDED', 'VOIDED', 'EXPIRED'
    ));

-- 2. Partial index for the expiry sweep, which scans AUTHORIZED rows by age.
--    Mirrors the settlement index: tiny, hot, and only the rows the sweep cares
--    about (terminal rows never qualify).
CREATE INDEX idx_transactions_expirable ON transactions(updated_at)
    WHERE status = 'AUTHORIZED';
