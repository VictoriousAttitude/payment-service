-- Optimistic locking: prevents lost updates from concurrent state transitions.
-- Without this, two concurrent captures both pass the in-memory state machine
-- and write duplicate ledger entry sets (each balanced, so per-transaction and
-- global balance checks cannot detect the duplication).
ALTER TABLE transactions ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
