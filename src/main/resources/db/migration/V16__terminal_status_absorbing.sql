-- Terminal payment statuses are absorbing: once a transaction reaches a terminal
-- state (SETTLED, FAILED, REFUNDED, VOIDED, EXPIRED) its lifecycle is over and the
-- status must never change again. PaymentStatus.canTransitionTo already returns
-- false for every terminal state, so the application cannot emit such a move; this
-- enforces the same invariant in the DB so a bug or a rogue UPDATE cannot revive a
-- settled or refunded payment behind the code's back.
--
-- Only a status CHANGE out of a terminal state is rejected. Unrelated column writes
-- on a terminal row (e.g. a late provider_reference, an optimistic-lock version
-- bump) stay allowed - the invariant is about the lifecycle label, not the row.
--
-- AFTER (not BEFORE) is deliberate and load-bearing. A BEFORE UPDATE row trigger
-- can rewrite NEW, so Postgres conservatively takes the strong FOR UPDATE row lock
-- on every update (the FOR NO KEY UPDATE optimization is disabled whenever such a
-- trigger exists). FOR UPDATE conflicts with the FOR KEY SHARE that a concurrent
-- ledger insert holds on this row through its FK, so two concurrent captures of one
-- transaction deadlock. An AFTER trigger cannot change the row, so the lock stays
-- FOR NO KEY UPDATE and there is no conflict; a RAISE here still aborts the update.
CREATE FUNCTION reject_terminal_status_change() RETURNS trigger AS $$
BEGIN
    IF OLD.status IN ('SETTLED', 'FAILED', 'REFUNDED', 'VOIDED', 'EXPIRED')
       AND NEW.status IS DISTINCT FROM OLD.status THEN
        RAISE EXCEPTION
            'transaction % is terminal (%) and cannot transition to %',
            OLD.id, OLD.status, NEW.status;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_transactions_terminal_absorbing
    AFTER UPDATE ON transactions
    FOR EACH ROW EXECUTE FUNCTION reject_terminal_status_change();
