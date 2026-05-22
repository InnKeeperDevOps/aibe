-- Audit log of every Claude CLI invocation: the prompt/command sent and the raw output returned.
CREATE TABLE IF NOT EXISTS claude_cli_logs (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    request_id      INTEGER,
    operation_type  VARCHAR(255),
    model           VARCHAR(255),
    working_dir     VARCHAR(1000),
    command         TEXT,
    prompt          TEXT,
    raw_output      TEXT,
    exit_code       INTEGER,
    duration_ms     INTEGER,
    created_at      TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_claude_cli_logs_created_at ON claude_cli_logs(created_at);
