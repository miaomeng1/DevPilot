ALTER TABLE cicd_onboarding ADD COLUMN credentials_updated_at TIMESTAMP NULL;
UPDATE cicd_onboarding SET credentials_updated_at = created_at WHERE request_cipher IS NOT NULL;
