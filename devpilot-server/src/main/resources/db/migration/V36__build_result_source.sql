-- Existing terminal records remain immutable: do not guess their provenance.
ALTER TABLE cicd_pipeline_run ADD COLUMN build_result_source VARCHAR(32) NOT NULL DEFAULT 'CALLBACK';
