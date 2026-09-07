-- Retain deduplication evidence after an application is deleted. No credentials.
CREATE TABLE application_creation_request (
    created_by BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    application_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (created_by, request_id)
);
