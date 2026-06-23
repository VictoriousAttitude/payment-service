-- Deferred double-entry balance invariant. The application balances every entry
-- set before posting (LedgerService.validateBalance) and the reconciliation
-- sweep detects imbalance after the fact. This makes imbalance impossible to
-- COMMIT in the first place: prevention over detection, enforced in the DB so no
-- bug or rogue writer can leave the ledger unbalanced behind the code's back.
--
-- DEFERRABLE INITIALLY DEFERRED is essential: the legs of a posting are inserted
-- one row at a time, so the set is only balanced once every row exists. Checking
-- at COMMIT - not per statement - is the one point the whole set is visible.
-- Scope is per transaction, per currency; a transaction is single-currency, so
-- the GROUP BY is a defensive guard, not cross-currency netting.
CREATE FUNCTION assert_ledger_balanced() RETURNS trigger AS $$
DECLARE
    unbalanced_currency TEXT;
BEGIN
    SELECT currency INTO unbalanced_currency
    FROM ledger_entries
    WHERE transaction_id = NEW.transaction_id
    GROUP BY currency
    HAVING sum(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END) <> 0
    LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION 'ledger imbalance for transaction % in currency %',
            NEW.transaction_id, unbalanced_currency;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_ledger_balanced();
