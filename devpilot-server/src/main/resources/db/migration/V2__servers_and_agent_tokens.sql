CREATE TABLE server_node (
    id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    hostname VARCHAR(255),
    ip VARCHAR(64),
    os VARCHAR(128),
    kernel VARCHAR(128),
    architecture VARCHAR(64),
    cpu_model VARCHAR(255),
    cpu_cores INT,
    memory_total BIGINT,
    disk_total BIGINT,
    agent_version VARCHAR(32),
    agent_status VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
    last_heartbeat TIMESTAMP(6),
    registered_at TIMESTAMP(6),
    created_by BIGINT NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_server_created_by FOREIGN KEY (created_by) REFERENCES sys_user (id)
);

CREATE TABLE agent_token (
    id BIGINT NOT NULL,
    server_id BIGINT NOT NULL,
    token_prefix VARCHAR(20) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_by BIGINT NOT NULL,
    last_used_at TIMESTAMP(6),
    revoked_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_agent_token_server FOREIGN KEY (server_id) REFERENCES server_node (id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_token_creator FOREIGN KEY (created_by) REFERENCES sys_user (id)
);

CREATE INDEX idx_server_agent_status ON server_node (agent_status);
CREATE INDEX idx_server_last_heartbeat ON server_node (last_heartbeat);
CREATE INDEX idx_agent_token_server ON agent_token (server_id);

