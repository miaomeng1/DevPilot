ALTER TABLE cicd_configuration ADD COLUMN preview_enabled TINYINT NOT NULL DEFAULT 0;
ALTER TABLE cicd_configuration ADD COLUMN preview_url_template VARCHAR(1000);
ALTER TABLE cicd_configuration ADD COLUMN preview_ttl_hours INT NOT NULL DEFAULT 72;
ALTER TABLE cicd_configuration ADD COLUMN preview_callback_secret_cipher TEXT;

CREATE TABLE cicd_preview (
    id BIGINT NOT NULL,
    application_id BIGINT NOT NULL,
    pull_request_id INT NOT NULL,
    external_run_id VARCHAR(255) NOT NULL,
    title VARCHAR(500),
    branch_name VARCHAR(255) NOT NULL,
    commit_sha VARCHAR(64) NOT NULL,
    image_uri VARCHAR(1000) NOT NULL,
    preview_url VARCHAR(1000),
    provider VARCHAR(32) NOT NULL,
    provider_deployment_id VARCHAR(255),
    status VARCHAR(32) NOT NULL,
    run_url VARCHAR(1000),
    failure_reason VARCHAR(1000),
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_cicd_preview_pull_request UNIQUE (application_id, pull_request_id),
    CONSTRAINT fk_cicd_preview_application FOREIGN KEY (application_id) REFERENCES application (id) ON DELETE CASCADE
);

CREATE INDEX idx_cicd_preview_expiry ON cicd_preview (status, expires_at);
