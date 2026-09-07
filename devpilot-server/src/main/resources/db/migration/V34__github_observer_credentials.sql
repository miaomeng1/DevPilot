CREATE TABLE github_observer_configuration (
    application_id BIGINT PRIMARY KEY,
    revision VARCHAR(36) NOT NULL,
    repository_url VARCHAR(1000) NOT NULL,
    branch_name VARCHAR(255) NOT NULL,
    token_cipher LONGTEXT NULL,
    expires_at TIMESTAMP NULL,
    enabled INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL
);
