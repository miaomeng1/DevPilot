CREATE TABLE nginx_host_snapshot (
    server_id BIGINT NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 0,
    available TINYINT NOT NULL DEFAULT 0,
    nginx_version VARCHAR(120),
    config_path VARCHAR(1000),
    error_message VARCHAR(1000),
    collected_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (server_id),
    CONSTRAINT fk_nginx_host_server FOREIGN KEY (server_id) REFERENCES server_node (id) ON DELETE CASCADE
);

CREATE TABLE nginx_config (
    id BIGINT NOT NULL,
    server_id BIGINT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    active TINYINT NOT NULL DEFAULT 1,
    last_seen_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_nginx_config_file UNIQUE (server_id, filename),
    CONSTRAINT fk_nginx_config_server FOREIGN KEY (server_id) REFERENCES server_node (id) ON DELETE CASCADE
);

CREATE INDEX idx_nginx_config_server ON nginx_config (server_id, active, filename);

CREATE TABLE nginx_command (
    id BIGINT NOT NULL,
    server_id BIGINT NOT NULL,
    config_id BIGINT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    action VARCHAR(32) NOT NULL,
    desired_content MEDIUMTEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'REQUESTED',
    requested_by BIGINT NOT NULL,
    validation_output TEXT,
    error_message VARCHAR(2000),
    requested_at TIMESTAMP(6) NOT NULL,
    claimed_at TIMESTAMP(6),
    completed_at TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_nginx_command_server FOREIGN KEY (server_id) REFERENCES server_node (id) ON DELETE CASCADE,
    CONSTRAINT fk_nginx_command_config FOREIGN KEY (config_id) REFERENCES nginx_config (id) ON DELETE CASCADE,
    CONSTRAINT fk_nginx_command_user FOREIGN KEY (requested_by) REFERENCES sys_user (id)
);

CREATE INDEX idx_nginx_command_queue ON nginx_command (server_id, status, requested_at);

CREATE TABLE nginx_config_history (
    id BIGINT NOT NULL,
    config_id BIGINT NOT NULL,
    server_id BIGINT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    old_content MEDIUMTEXT NOT NULL,
    new_content MEDIUMTEXT NOT NULL,
    action VARCHAR(32) NOT NULL,
    operator_id BIGINT NOT NULL,
    command_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    error_message VARCHAR(2000),
    created_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_nginx_history_config FOREIGN KEY (config_id) REFERENCES nginx_config (id) ON DELETE CASCADE,
    CONSTRAINT fk_nginx_history_server FOREIGN KEY (server_id) REFERENCES server_node (id) ON DELETE CASCADE,
    CONSTRAINT fk_nginx_history_operator FOREIGN KEY (operator_id) REFERENCES sys_user (id),
    CONSTRAINT fk_nginx_history_command FOREIGN KEY (command_id) REFERENCES nginx_command (id) ON DELETE CASCADE
);

CREATE INDEX idx_nginx_history_config ON nginx_config_history (config_id, created_at);
