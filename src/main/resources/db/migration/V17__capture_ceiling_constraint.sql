-- Deferred capture/refund ceiling invariant, the DB twin of
-- TransactionRepository.findAmountInvariantViolations. Two money-safety limits
-- the application already enforces (capturePayment/refundPayment guard against
-- over-capture and over-refund) are pinned here so a duplicated capture set, a
-- runaway refund, or any rogue writer cannot push a transaction past its limits
-- behind the code's back:
--   captured (sum of INCOMING DEBIT)  <= authorized amount
--   refunded (sum of OUTGOING CREDIT) <= captured
-- Both corruptions stay balanced per-transaction (debit == credit), so the V15
-- imbalance check is blind to them; this is the only DB check that compares
-- entry totals against the source-of-truth amount and against each other.
--
-- Chargebacks post to MERCHANT/CHARGEBACK/PLATFORM, never INCOMING/OUTGOING, so
-- a clawback never counts as a capture or a refund and never trips this check -
-- identical to how the reconciliation query scopes the same sums.
--
-- DEFERRABLE INITIALLY DEFERRED: a posting's legs are inserted one row at a
-- time, so the totals are only final once every row of the set exists. Checking
-- at COMMIT - not per statement - is the one point the whole set is visible.
--
-- This guards the single-transaction case (an application bug emitting an
-- over-ceiling set in one commit). It cannot stop aggregate write-skew - two
-- concurrent captures on one authorization, each in its own transaction, each
-- seeing only its own snapshot - which remains owned by the @Version optimistic
-- lock. Defense in depth, not a replacement for it.
CREATE FUNCTION assert_capture_ceiling() RETURNS trigger AS $$
DECLARE
    authorized_amount BIGINT;
    captured          BIGINT;
    refunded          BIGINT;
BEGIN
    SELECT amount INTO authorized_amount
    FROM transactions
    WHERE id = NEW.transaction_id;

    SELECT
        coalesce(sum(amount) FILTER (
            WHERE account_type = 'INCOMING' AND entry_type = 'DEBIT'), 0),
        coalesce(sum(amount) FILTER (
            WHERE account_type = 'OUTGOING' AND entry_type = 'CREDIT'), 0)
    INTO captured, refunded
    FROM ledger_entries
    WHERE transaction_id = NEW.transaction_id;

    IF captured > authorized_amount THEN
        RAISE EXCEPTION
            'capture ceiling exceeded for transaction %: captured % > authorized %',
            NEW.transaction_id, captured, authorized_amount;
    END IF;

    IF refunded > captured THEN
        RAISE EXCEPTION
            'refund ceiling exceeded for transaction %: refunded % > captured %',
            NEW.transaction_id, refunded, captured;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_ledger_entries_capture_ceiling
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_capture_ceiling();
