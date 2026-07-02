-- Posting groups decouple the ledger's atomicity unit from payment
-- transactions. Until now every entry hung off a transaction_id and the V15
-- balance trigger grouped by it. Payouts and reserve releases post entries
-- with no payment transaction; a NULL transaction_id would make the V15 check
-- vacuous (WHERE transaction_id = NULL matches nothing), letting an unbalanced
-- treasury posting COMMIT unchecked. posting_group_id is the new, always
-- NOT NULL grouping key: for payment-driven postings it equals the transaction
-- id, for treasury postings it is the payout/hold id.
--
-- This must stay ONE migration file: Flyway wraps it in a single transaction,
-- so the immutability trigger is never observably disabled and SET NOT NULL
-- sees the completed backfill.

ALTER TABLE ledger_entries ADD COLUMN posting_group_id UUID;

-- The V7 BEFORE UPDATE trigger rejects any UPDATE, including this backfill.
-- DISABLE takes SHARE ROW EXCLUSIVE, so no concurrent write slips through an
-- unprotected window, and the transaction re-enables it before COMMIT.
ALTER TABLE ledger_entries DISABLE TRIGGER trg_ledger_entries_immutable;

UPDATE ledger_entries SET posting_group_id = transaction_id;

ALTER TABLE ledger_entries ENABLE TRIGGER trg_ledger_entries_immutable;

ALTER TABLE ledger_entries ALTER COLUMN posting_group_id SET NOT NULL;

CREATE INDEX idx_ledger_entries_posting_group ON ledger_entries(posting_group_id);

-- transaction_id becomes optional: treasury postings (payouts, reserve
-- releases) have none. The FK stays - a non-NULL value must still reference a
-- real transaction; NULL is simply unenforced, as FKs are.
ALTER TABLE ledger_entries ALTER COLUMN transaction_id DROP NOT NULL;

-- Retarget the V15 balance invariant at the posting group. OR REPLACE swaps
-- the function body under the existing constraint trigger; the trigger itself
-- (deferred, at COMMIT) is untouched.
CREATE OR REPLACE FUNCTION assert_ledger_balanced() RETURNS trigger AS $$
DECLARE
    unbalanced_currency TEXT;
BEGIN
    SELECT currency INTO unbalanced_currency
    FROM ledger_entries
    WHERE posting_group_id = NEW.posting_group_id
    GROUP BY currency
    HAVING sum(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END) <> 0
    LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION 'ledger imbalance for posting group % in currency %',
            NEW.posting_group_id, unbalanced_currency;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- The V17 ceiling check is per-transaction by definition; entries with no
-- transaction (treasury postings) are out of scope. It was only accidentally
-- NULL-safe before (the WHERE clause matched nothing); make the skip explicit.
CREATE OR REPLACE FUNCTION assert_capture_ceiling() RETURNS trigger AS $$
DECLARE
    authorized_amount BIGINT;
    captured          BIGINT;
    refunded          BIGINT;
BEGIN
    IF NEW.transaction_id IS NULL THEN
        RETURN NULL;
    END IF;

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
