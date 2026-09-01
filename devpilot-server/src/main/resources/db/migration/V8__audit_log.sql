CREATE TABLE audit_log (
    id BIGINT NOT NULL,
    user_id BIGINT,
    username VARCHAR(64),
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(40) NOT NULL,
    resource_id VARCHAR(100),
    resource_name VARCHAR(255),
    server_id BIGINT,
    ip_address VARCHAR(64),
    request_params TEXT,
    result VARCHAR(20) NOT NULL,
    error_message VARCHAR(1000),
    occurred_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_audit_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE SET NULL,
    CONSTRAINT fk_audit_server FOREIGN KEY (server_id) REFERENCES server_node (id) ON DELETE SET NULL
);

CREATE INDEX idx_audit_time ON audit_log (occurred_at);
CREATE INDEX idx_audit_action_result ON audit_log (action, result, occurred_at);
CREATE INDEX idx_audit_user_time ON audit_log (user_id, occurred_at);

