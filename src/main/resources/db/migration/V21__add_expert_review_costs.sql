-- One row per expert review run: model, token usage, dollar cost, and duration.
-- Roll-ups and budget controls read from this ledger.
CREATE TABLE IF NOT EXISTS expert_review_costs (
    id                            INTEGER PRIMARY KEY AUTOINCREMENT,
    suggestion_id                 INTEGER NOT NULL,
    expert_name                   VARCHAR(100) NOT NULL,
    operation_type                VARCHAR(255),
    review_session_id             VARCHAR(100),
    model                         VARCHAR(255),
    input_tokens                  INTEGER NOT NULL DEFAULT 0,
    output_tokens                 INTEGER NOT NULL DEFAULT 0,
    cache_read_input_tokens       INTEGER NOT NULL DEFAULT 0,
    cache_creation_input_tokens   INTEGER NOT NULL DEFAULT 0,
    cost_usd                      NUMERIC(19, 6) NOT NULL DEFAULT 0,
    duration_ms                   INTEGER NOT NULL DEFAULT 0,
    created_at                    TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_expert_review_costs_suggestion
    ON expert_review_costs(suggestion_id);

CREATE INDEX IF NOT EXISTS idx_expert_review_costs_created_at
    ON expert_review_costs(created_at);
