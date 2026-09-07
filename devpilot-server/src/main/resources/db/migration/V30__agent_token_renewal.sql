CREATE TABLE agent_token_renewal_request (
    created_by BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    server_id BIGINT NOT NULL,
    expected_revision VARCHAR(64) NOT NULL,
    response_cipher LONGTEXT,
    expires_at TIMESTAMP NOT NULL,
    PRIMARY KEY (created_by, request_id)
);
CREATE INDEX idx_agent_renewal_server ON agent_token_renewal_request(server_id);
CREATE INDEX idx_agent_renewal_expiry ON agent_token_renewal_request(expires_at);
