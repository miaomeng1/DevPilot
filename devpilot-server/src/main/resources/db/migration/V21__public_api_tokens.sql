CREATE TABLE api_access_token (
    id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    token_prefix VARCHAR(24) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    scope VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL,
    expires_at TIMESTAMP(6),
    last_used_at TIMESTAMP(6),
    created_by BIGINT NOT NULL,
    revoked_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_api_access_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_api_access_token_creator FOREIGN KEY (created_by) REFERENCES sys_user (id) ON DELETE CASCADE
);

CREATE INDEX idx_api_access_token_auth ON api_access_token (token_hash, status, expires_at);
