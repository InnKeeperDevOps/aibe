-- List of folders the AI is allowed to read/modify in the target repository.
-- Newline-separated, relative to the repo root. NULL or blank = no restriction
-- (AI works on the whole repo, the historic behaviour).
ALTER TABLE site_settings ADD COLUMN managed_folders TEXT;
