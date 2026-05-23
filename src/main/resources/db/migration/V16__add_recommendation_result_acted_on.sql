-- Track which generated recommendation bullets have been turned into tracked
-- suggestions. The row is never deleted; instead its status flips to ACTED_ON
-- and the linked suggestion id is recorded. This lets the active list drop
-- acted-on bullets while history keeps the full record.
ALTER TABLE recommendation_results ADD COLUMN status VARCHAR(50) NOT NULL DEFAULT 'PENDING';
ALTER TABLE recommendation_results ADD COLUMN acted_on_suggestion_id INTEGER;

CREATE INDEX IF NOT EXISTS idx_recommendation_results_status ON recommendation_results(status);
