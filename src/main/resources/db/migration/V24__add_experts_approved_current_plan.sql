-- Tracks whether the most recent AI expert review pass approved the current
-- plan version without proposing any changes. Reset to 0 whenever the plan
-- text is rewritten (either by the main AI in response to admin feedback or
-- by an expert proposing changes). Used by approvePlan to decide whether
-- admin approval should trigger task generation (both gates met) or another
-- expert-review pass.
ALTER TABLE suggestions ADD COLUMN experts_approved_current_plan BOOLEAN NOT NULL DEFAULT 0;
