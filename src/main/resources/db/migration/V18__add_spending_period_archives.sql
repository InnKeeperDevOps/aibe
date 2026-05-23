-- Archived totals for completed daily and monthly spending windows. When the
-- active reset window rolls over (a new UTC day starts, or a new UTC month
-- starts), the just-completed window is summarized into a row here so the
-- prior period's total remains visible in history and dashboards even though
-- the live windowed query for the new window returns zero. The new window is
-- not blocked from starting fresh — newly allowed work proceeds at zero while
-- the prior period's number is preserved here.
--
-- The uniqueness constraint on (period_type, period_start) makes archival
-- idempotent: if two processes try to archive the same completed window the
-- second insert fails with an integrity violation and the service treats it
-- as "already archived".
CREATE TABLE IF NOT EXISTS spending_period_archives (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    period_type           VARCHAR(20) NOT NULL,
    period_start          TIMESTAMP NOT NULL,
    period_end            TIMESTAMP NOT NULL,
    total_cost_usd        NUMERIC(19, 6) NOT NULL DEFAULT 0,
    total_reviews         INTEGER NOT NULL DEFAULT 0,
    limit_at_period_end   NUMERIC(19, 6),
    archived_at           TIMESTAMP NOT NULL,
    CONSTRAINT uq_spending_period_archives
        UNIQUE (period_type, period_start)
);

CREATE INDEX IF NOT EXISTS idx_spending_period_archives_period_start
    ON spending_period_archives(period_start);
