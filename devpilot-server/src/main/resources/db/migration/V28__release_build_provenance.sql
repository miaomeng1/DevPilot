ALTER TABLE cicd_pipeline_run ADD COLUMN build_external_run_id VARCHAR(255);
ALTER TABLE cicd_pipeline_run ADD COLUMN approval_actor VARCHAR(255);
ALTER TABLE cicd_pipeline_run ADD COLUMN approved_at TIMESTAMP NULL;
