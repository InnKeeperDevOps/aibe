-- Per-suggestion and global spend caps configurable by admins. NULL means
-- no cap is enforced. global_cost_reset_period controls how often the
-- running total is treated as reset (DAILY | MONTHLY | NEVER).
ALTER TABLE site_settings ADD COLUMN max_cost_per_suggestion_usd NUMERIC(19, 6);
ALTER TABLE site_settings ADD COLUMN max_total_cost_usd NUMERIC(19, 6);
ALTER TABLE site_settings ADD COLUMN global_cost_reset_period VARCHAR(20) NOT NULL DEFAULT 'NEVER';
