ALTER TABLE site_settings ADD COLUMN claude_config TEXT;
UPDATE site_settings SET claude_config = '{"theme":"light","hasCompletedOnboarding":true}' WHERE claude_config IS NULL;
