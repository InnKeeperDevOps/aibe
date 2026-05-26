-- Persist the most recent set of clarification Q&A so the suggestion can be
-- retried if the Claude call that processes those answers fails (process
-- error, timeout, etc.). Stores a JSON array of {"question","answer"} pairs.
-- Cleared on the next successful AI roundtrip.
ALTER TABLE suggestions ADD COLUMN last_clarification_answers TEXT;
