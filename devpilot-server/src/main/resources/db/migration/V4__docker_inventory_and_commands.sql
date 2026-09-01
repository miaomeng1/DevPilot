CREATE TABLE docker_host_snapshot (
    server_id BIGINT NOT NULL,
    available TINYINT NOT NULL DEFAULT 0,
    engine_version VARCHAR(64),
    error_message VARCHAR(500),
    image_count INT NOT NULL DEFAULT 0,
    volume_count INT NOT NULL DEFAULT 0,
    network_count INT NOT NULL DEFAULT 0,
    collected_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (server_id),
    CONSTRAINT fk_docker_host_server FOREIGN KEY (server_id) REFERENCES server_node (id) ON DELETE CASCADE
);

CREATE TABLE docker_container_snapshot (
    id BIGINT NOT NULL,
    server_id BIGINT NOT NULL,
    container_id VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    image VARCHAR(500) NOT NULL,
    state VARCHAR(32) NOT NULL,
    status VARCHAR(255),
    health VARCHAR(32),
    cpu_usage DOUBLE NOT NULL DEFAULT 0,
    memory_usage BIGINT NOT NULL DEFAULT 0,
    memory_limit BIGINT NOT NULL DEFAULT 0,
    network_rx BIGINT NOT NULL DEFAULT 0,
    network_tx BIGINT NOT NULL DEFAULT 0,
    ip_address VARCHAR(64),
    ports_json TEXT,
    container_created_at TIMESTAMP(6),
    started_at TIMESTAMP(6),
    restart_count INT NOT NULL DEFAULT 0,
    network_mode VARCHAR(128),
    volumes_json TEXT,
    environment_json TEXT,
    active TINYINT NOT NULL DEFAULT 1,
    last_seen_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_docker_container UNIQUE (server_id, container_id),
    CONSTRAINT fk_docker_container_server FOREIGN KEY (server_id) REFERENCES server_node (id) ON DELETE CASCADE
);

CREATE INDEX idx_docker_container_server_state ON docker_container_snapshot (server_id, active, state);

CREATE TABLE docker_command (
    id BIGINT NOT NULL,
    server_id BIGINT NOT NULL,
    container_snapshot_id BIGINT NOT NULL,
    container_id VARCHAR(64) NOT NULL,
    action VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'REQUESTED',
    requested_by BIGINT NOT NULL,
    error_message VARCHAR(1000),
    requested_at TIMESTAMP(6) NOT NULL,
    claimed_at TIMESTAMP(6),
    completed_at TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_docker_command_server FOREIGN KEY (server_id) REFERENCES server_node (id) ON DELETE CASCADE,
    CONSTRAINT fk_docker_command_container FOREIGN KEY (container_snapshot_id) REFERENCES docker_container_snapshot (id) ON DELETE CASCADE,
    CONSTRAINT fk_docker_command_user FOREIGN KEY (requested_by) REFERENCES sys_user (id)
);

CREATE INDEX idx_docker_command_agent_queue ON docker_command (server_id, status, requested_at);
