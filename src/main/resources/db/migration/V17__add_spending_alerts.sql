-- Proactive spending alert configuration on the existing site_settings row.
-- spending_alerts_enabled toggles the whole alerting feature; thresholds is a
-- CSV of percent-of-cap values that trigger warnings (default "75,90") and
-- recipients is a server-side list of usernames or email addresses that
-- alerts are addressed to (NULL falls back to admin-role users only).
ALTER TABLE site_settings ADD COLUMN spending_alerts_enabled BOOLEAN NOT NULL DEFAULT 1;
ALTER TABLE site_settings ADD COLUMN spending_alert_thresholds VARCHAR(255) DEFAULT '75,90';
ALTER TABLE site_settings ADD COLUMN spending_alert_recipients TEXT;

-- One row per fired alert. The uniqueness over (scope, scope_key, window_key,
-- threshold_percent) is what guarantees a given threshold cannot fire twice
-- per window even under concurrent recordings — the second insert simply
-- raises an integrity violation that the service treats as "already alerted".
CREATE TABLE IF NOT EXISTS spending_alert_state (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    scope              VARCHAR(20) NOT NULL,
    scope_key          VARCHAR(64) NOT NULL,
    window_key         VARCHAR(64) NOT NULL,
    threshold_percent  INTEGER NOT NULL,
    spend_at_alert     NUMERIC(19, 6) NOT NULL DEFAULT 0,
    limit_at_alert     NUMERIC(19, 6) NOT NULL DEFAULT 0,
    fired_at           TIMESTAMP NOT NULL,
    CONSTRAINT uq_spending_alert_state
        UNIQUE (scope, scope_key, window_key, threshold_percent)
);

CREATE INDEX IF NOT EXISTS idx_spending_alert_state_scope_key
    ON spending_alert_state(scope, scope_key, window_key);
