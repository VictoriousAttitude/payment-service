-- Epoch Merkle anchoring: tamper-EVIDENCE for the ledger. The V7 trigger stops
-- the application from mutating history, but a superuser or a tampered
-- backup-restore is silent. Each epoch seals a batch of entries under an
-- RFC 6962 Merkle root, and roots are hash-chained, so any later change to an
-- anchored row is detectable by recomputation (Certificate Transparency / QLDB
-- pattern).
--
-- Membership is EXPLICIT (one leaf row per entry) instead of a serial range:
-- ledger_entries has UUID keys, and a serial column assigned at insert time
-- cannot define epoch boundaries because commit order differs from assignment
-- order (a long transaction could fill a hole inside an already-sealed range).
-- With explicit membership a late-committing entry simply lands in the next
-- epoch. Same idea as Trillian's sequencer table.
CREATE TABLE ledger_anchors (
    epoch      BIGINT PRIMARY KEY CHECK (epoch > 0),
    root       VARCHAR(64) NOT NULL,
    chain_hash VARCHAR(64) NOT NULL,
    leaf_count INT NOT NULL CHECK (leaf_count > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_anchor_leaves (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    epoch      BIGINT NOT NULL REFERENCES ledger_anchors(epoch),
    leaf_index INT NOT NULL CHECK (leaf_index >= 0),
    entry_id   UUID NOT NULL REFERENCES ledger_entries(id),
    -- leaf order inside an epoch is part of what the root commits to
    CONSTRAINT uq_anchor_leaves_position UNIQUE (epoch, leaf_index),
    -- an entry is anchored exactly once, ever
    CONSTRAINT uq_anchor_leaves_entry UNIQUE (entry_id)
);

-- Anchors are as immutable as the ledger they attest to; rewriting an anchor
-- is exactly the attack this table exists to expose.
CREATE FUNCTION reject_anchor_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_anchors_immutable
    BEFORE UPDATE OR DELETE ON ledger_anchors
    FOR EACH ROW EXECUTE FUNCTION reject_anchor_mutation();

CREATE TRIGGER trg_ledger_anchor_leaves_immutable
    BEFORE UPDATE OR DELETE ON ledger_anchor_leaves
    FOR EACH ROW EXECUTE FUNCTION reject_anchor_mutation();
