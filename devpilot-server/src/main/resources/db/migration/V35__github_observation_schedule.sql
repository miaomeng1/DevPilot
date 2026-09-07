ALTER TABLE github_observer_configuration ADD COLUMN next_check_at TIMESTAMP NULL;
ALTER TABLE github_observer_configuration ADD COLUMN check_lease VARCHAR(36) NULL;
ALTER TABLE cicd_pipeline_run ADD COLUMN github_checked_at TIMESTAMP NULL;
ALTER TABLE cicd_pipeline_run ADD COLUMN github_observation VARCHAR(64) NULL;
