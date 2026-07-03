-- Acquirer settlement files ingested for three-way reconciliation. The raw
-- content is kept for audit/replay: verdict + content + ledger is enough to
-- re-derive every matched line, so matches are deliberately not persisted
-- (no signal); only discrepancies are.
CREATE TABLE settlement_files (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    filename VARCHAR(255) NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('RECEIVED', 'PROCESSED', 'FAILED')),
    line_count INT NOT NULL DEFAULT 0,
    matched_count INT NOT NULL DEFAULT 0,
    pending_count INT NOT NULL DEFAULT 0,
    discrepancy_count INT NOT NULL DEFAULT 0,
    failure_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0
);

-- Idempotency: a byte-identical re-upload maps to the existing row and its
-- persisted verdict instead of a second reconciliation run.
CREATE UNIQUE INDEX uq_settlement_files_sha ON settlement_files(content_sha256);

CREATE TABLE settlement_file_discrepancies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    file_id UUID NOT NULL REFERENCES settlement_files(id),
    reference VARCHAR(255) NOT NULL,
    type VARCHAR(30) NOT NULL CHECK (type IN ('MISSING_IN_LEDGER','MISSING_IN_SETTLEMENT','KIND_MISMATCH','CURRENCY_MISMATCH','GROSS_MISMATCH','FEE_MISMATCH','DUPLICATE_REFERENCE')),
    detail TEXT NOT NULL,
    ledger_value VARCHAR(100),
    settlement_value VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_sfd_file ON settlement_file_discrepancies(file_id);
