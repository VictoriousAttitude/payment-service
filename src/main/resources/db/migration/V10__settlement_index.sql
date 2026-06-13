-- Supports the settlement batch's claim scan: captured transactions ordered by
-- their settlement clock (updated_at == capture time for a CAPTURED row). The
-- existing idx_transactions_active only covers CREATED/PENDING/AUTHORIZED, so
-- without this the batch would seq-scan the whole table every run.
CREATE INDEX idx_transactions_settlable ON transactions(updated_at)
    WHERE status = 'CAPTURED';
