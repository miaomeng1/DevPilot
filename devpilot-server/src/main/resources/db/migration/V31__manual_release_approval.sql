CREATE TABLE cicd_release_approval (
    id VARCHAR(36) PRIMARY KEY,
    request_id VARCHAR(36) NOT NULL,
    application_id BIGINT NOT NULL,
    build_run_id BIGINT NOT NULL,
    build_external_run_id VARCHAR(255) NOT NULL,
    commit_sha VARCHAR(64) NOT NULL,
    image_uri VARCHAR(1000) NOT NULL,
    environment VARCHAR(32) NOT NULL,
    server_id BIGINT NOT NULL,
    configuration_updated_at TIMESTAMP NOT NULL,
    configuration_fingerprint VARCHAR(64) NOT NULL,
    approved_by BIGINT NOT NULL,
    approved_username VARCHAR(64) NOT NULL,
    approved_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    consumed_by_run_id VARCHAR(255),
    consumed_at TIMESTAMP,
    UNIQUE (approved_by, request_id)
);
CREATE INDEX idx_release_approval_application ON cicd_release_approval(application_id, approved_at);
