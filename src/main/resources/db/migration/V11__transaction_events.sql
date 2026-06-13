-- Append-only history of every transaction status transition. The transactions
-- row holds only the current status; this is the audit trail for disputes,
-- debugging and compliance. Written in the same transaction as the status
-- change, so the history can never diverge from the state it records.
CREATE TABLE transaction_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transactions(id),
    from_status VARCHAR(20),
    to_status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- History is read by transaction, oldest first.
CREATE INDEX idx_transaction_events_txn ON transaction_events(transaction_id, created_at);

-- Immutable like the ledger: an audit log you can rewrite is not an audit log.
-- A separate function from reject_ledger_mutation so the error names this table.
CREATE FUNCTION reject_transaction_event_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'transaction_events are immutable: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_transaction_events_immutable
    BEFORE UPDATE OR DELETE ON transaction_events
    FOR EACH ROW EXECUTE FUNCTION reject_transaction_event_mutation();
