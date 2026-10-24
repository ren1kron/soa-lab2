CREATE TABLE acquisition_journal (
    source_id BIGINT PRIMARY KEY CHECK (source_id > 0),
    acquirer_id BIGINT NOT NULL CHECK (acquirer_id > 0 AND acquirer_id <> source_id),
    employee_ids JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
