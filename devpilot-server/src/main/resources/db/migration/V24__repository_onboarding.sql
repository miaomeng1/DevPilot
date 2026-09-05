CREATE TABLE cicd_onboarding (
    id VARCHAR(36) PRIMARY KEY,
    application_id BIGINT NOT NULL UNIQUE,
    request_cipher LONGTEXT,
    stage INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    resource_id VARCHAR(255),
    runtime_key VARCHAR(600),
    change_url VARCHAR(1000),
    error_message VARCHAR(2000),
    lease_token VARCHAR(36),
    lease_until TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_onboarding_application FOREIGN KEY (application_id) REFERENCES application(id) ON DELETE CASCADE
);
ALTER TABLE cicd_configuration ADD COLUMN provider_verified_at TIMESTAMP NULL;
ALTER TABLE cicd_configuration ADD COLUMN provider_verification_error VARCHAR(1000);
ALTER TABLE cicd_configuration ADD COLUMN callback_verified_at TIMESTAMP NULL;
