CREATE TABLE server_creation_request (
    created_by BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    server_id BIGINT NOT NULL,
    requested_name VARCHAR(100) NOT NULL,
    response_cipher LONGTEXT,
    expires_at TIMESTAMP NOT NULL,
    PRIMARY KEY (created_by, request_id)
);
CREATE INDEX idx_server_creation_expiry ON server_creation_request(expires_at);
