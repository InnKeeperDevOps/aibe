-- Persistent record of every recommendation run kicked off by an admin and the
-- generated recommendations it produced. Replaces the previous in-memory map so
-- runs survive restart and can be browsed later.
CREATE TABLE IF NOT EXISTS recommendation_runs (
    id                     INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id                VARCHAR(64) NOT NULL UNIQUE,
    status                 VARCHAR(50) NOT NULL,
    error_message          TEXT,
    requested_by_user_id   INTEGER,
    requested_by_username  VARCHAR(255),
    created_at             TIMESTAMP NOT NULL,
    updated_at             TIMESTAMP NOT NULL,
    completed_at           TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_recommendation_runs_task_id ON recommendation_runs(task_id);
CREATE INDEX IF NOT EXISTS idx_recommendation_runs_created_at ON recommendation_runs(created_at);
CREATE INDEX IF NOT EXISTS idx_recommendation_runs_status ON recommendation_runs(status);

CREATE TABLE IF NOT EXISTS recommendation_results (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id          INTEGER NOT NULL,
    result_order    INTEGER NOT NULL,
    title           VARCHAR(255) NOT NULL,
    description     TEXT,
    FOREIGN KEY (run_id) REFERENCES recommendation_runs(id)
);

CREATE INDEX IF NOT EXISTS idx_recommendation_results_run_id ON recommendation_results(run_id);
