CREATE TABLE cicd_configuration (
    id BIGINT NOT NULL,
    application_id BIGINT NOT NULL,
    repository_provider VARCHAR(32) NOT NULL,
    repository_url VARCHAR(1000) NOT NULL,
    branch_name VARCHAR(255) NOT NULL,
    deployment_provider VARCHAR(32) NOT NULL,
    deployment_webhook_cipher TEXT NOT NULL,
    callback_secret_cipher TEXT NOT NULL,
    auto_deploy TINYINT NOT NULL DEFAULT 1,
    production_approval TINYINT NOT NULL DEFAULT 1,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_cicd_configuration_application UNIQUE (application_id),
    CONSTRAINT fk_cicd_configuration_application FOREIGN KEY (application_id) REFERENCES application (id) ON DELETE CASCADE,
    CONSTRAINT fk_cicd_configuration_creator FOREIGN KEY (created_by) REFERENCES sys_user (id)
);

CREATE TABLE cicd_pipeline_run (
    id BIGINT NOT NULL,
    application_id BIGINT NOT NULL,
    external_run_id VARCHAR(255) NOT NULL,
    commit_sha VARCHAR(64) NOT NULL,
    branch_name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    test_status VARCHAR(32) NOT NULL,
    security_status VARCHAR(32) NOT NULL,
    image_uri VARCHAR(1000),
    image_digest VARCHAR(255),
    run_url VARCHAR(1000),
    summary TEXT,
    deploy_status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED',
    deploy_error VARCHAR(1000),
    started_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_cicd_pipeline_external UNIQUE (application_id, external_run_id),
    CONSTRAINT fk_cicd_pipeline_application FOREIGN KEY (application_id) REFERENCES application (id) ON DELETE CASCADE
);

CREATE INDEX idx_cicd_pipeline_recent ON cicd_pipeline_run (application_id, started_at);

