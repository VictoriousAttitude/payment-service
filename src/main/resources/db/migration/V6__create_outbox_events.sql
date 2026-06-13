-- Transactional outbox: provider-dispatch events written atomically with the
-- payment that produced them. A scheduled dispatcher drains PENDING rows,
-- guaranteeing the provider is contacted even if the app crashes after commit.
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id UUID NOT NULL,
    type VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DISPATCHED', 'FAILED')),
    attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    dispatched_at TIMESTAMP
);

-- Dispatcher scans only undispatched rows; partial index keeps it cheap as the
-- table grows with dispatched history.
CREATE INDEX idx_outbox_pending ON outbox_events(created_at) WHERE status = 'PENDING';
