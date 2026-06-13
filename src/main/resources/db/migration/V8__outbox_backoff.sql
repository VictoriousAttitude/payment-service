-- Retry backoff for the outbox. Without it, a failing event is re-selected on
-- every dispatch tick and hot-loops the provider. next_attempt_at gates when a
-- PENDING event becomes eligible again; recordFailure pushes it into the future
-- with exponential backoff. Existing rows are eligible immediately (now()).
ALTER TABLE outbox_events
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- Dispatcher claim path: PENDING rows that are due, oldest first.
CREATE INDEX idx_outbox_dispatchable ON outbox_events(next_attempt_at)
    WHERE status = 'PENDING';
