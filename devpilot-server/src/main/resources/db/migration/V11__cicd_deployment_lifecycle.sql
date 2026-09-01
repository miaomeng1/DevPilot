ALTER TABLE cicd_configuration ADD COLUMN deployment_mode VARCHAR(32) NOT NULL DEFAULT 'WEBHOOK';
ALTER TABLE cicd_configuration MODIFY COLUMN deployment_webhook_cipher TEXT NULL;
ALTER TABLE cicd_configuration ADD COLUMN provider_base_url_cipher TEXT;
ALTER TABLE cicd_configuration ADD COLUMN provider_api_token_cipher TEXT;
ALTER TABLE cicd_configuration ADD COLUMN provider_resource_id VARCHAR(255);
ALTER TABLE cicd_configuration ADD COLUMN auto_rollback TINYINT NOT NULL DEFAULT 1;
ALTER TABLE cicd_configuration ADD COLUMN health_timeout_seconds INT NOT NULL DEFAULT 120;

CREATE TABLE cicd_deployment (
    id BIGINT NOT NULL,
    application_id BIGINT NOT NULL,
    pipeline_run_id BIGINT,
    rollback_of_id BIGINT,
    deployment_kind VARCHAR(32) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    image_uri VARCHAR(1000) NOT NULL,
    previous_image_uri VARCHAR(1000),
    status VARCHAR(32) NOT NULL,
    provider_deployment_id VARCHAR(255),
    logs TEXT,
    triggered_by BIGINT,
    started_at TIMESTAMP(6) NOT NULL,
    health_deadline_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_cicd_deployment_application FOREIGN KEY (application_id) REFERENCES application (id) ON DELETE CASCADE,
    CONSTRAINT fk_cicd_deployment_pipeline FOREIGN KEY (pipeline_run_id) REFERENCES cicd_pipeline_run (id) ON DELETE SET NULL,
    CONSTRAINT fk_cicd_deployment_rollback_of FOREIGN KEY (rollback_of_id) REFERENCES cicd_deployment (id) ON DELETE SET NULL,
    CONSTRAINT fk_cicd_deployment_user FOREIGN KEY (triggered_by) REFERENCES sys_user (id) ON DELETE SET NULL
);

CREATE INDEX idx_cicd_deployment_application ON cicd_deployment (application_id, started_at);
CREATE INDEX idx_cicd_deployment_health ON cicd_deployment (status, health_deadline_at);
