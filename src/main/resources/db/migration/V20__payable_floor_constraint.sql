-- Deferred payable-floor invariant: a payout may never overdraw the merchant's
-- payable balance. The application already guards this (PayoutService takes a
-- FOR UPDATE lock on the merchant row and checks available funds), so this is
-- the DB twin - a rogue writer or an application bug cannot disburse money the
-- merchant does not have.
--
-- Scope is deliberate: ONLY payout postings are floored. A settled chargeback
-- also debits MERCHANT_PAYABLE and is ALLOWED to push it negative (the clawback
-- happens whether or not the merchant has funds; the hole is covered by later
-- settlements or reserve releases). The two postings are discriminated by
-- shape: a payout group contains a PAYOUT_CLEARING leg, a chargeback group
-- never does.
--
-- Leg audit:
--   payout            PAYABLE DEBIT + CLEARING CREDIT -> fires, EXISTS hits, checked
--   payout reversal   CLEARING DEBIT + PAYABLE CREDIT -> no PAYABLE DEBIT, WHEN skips
--   settlement split  PAYABLE CREDIT only             -> WHEN skips
--   reserve release   PAYABLE CREDIT only             -> WHEN skips
--   settled chargeback PAYABLE DEBIT, no CLEARING leg -> fires, EXISTS misses, allowed
--
-- DEFERRABLE INITIALLY DEFERRED: the group's legs insert one row at a time; the
-- balance including this posting is only final at COMMIT.
CREATE FUNCTION assert_payable_floor() RETURNS trigger AS $$
DECLARE
    payable BIGINT;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM ledger_entries
        WHERE posting_group_id = NEW.posting_group_id
          AND account_type = 'PAYOUT_CLEARING'
    ) THEN
        RETURN NULL;
    END IF;

    SELECT coalesce(sum(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END), 0)
    INTO payable
    FROM ledger_entries
    WHERE account_type = 'MERCHANT_PAYABLE'
      AND account_id = NEW.account_id
      AND currency = NEW.currency;

    IF payable < 0 THEN
        RAISE EXCEPTION
            'payable floor violated for account % in %: balance % < 0 after payout posting %',
            NEW.account_id, NEW.currency, payable, NEW.posting_group_id;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_ledger_entries_payable_floor
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.account_type = 'MERCHANT_PAYABLE' AND NEW.entry_type = 'DEBIT')
    EXECUTE FUNCTION assert_payable_floor();
