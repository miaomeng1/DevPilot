CREATE TABLE application (
    id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    code VARCHAR(64) NOT NULL,
    description VARCHAR(1000),
    environment VARCHAR(32) NOT NULL,
    server_id BIGINT NOT NULL,
    deploy_type VARCHAR(32) NOT NULL DEFAULT 'DOCKER',
    container_snapshot_id BIGINT,
    current_version VARCHAR(120),
    health_check_url VARCHAR(1000),
    access_url VARCHAR(1000),
    status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    health_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    health_message VARCHAR(500),
    health_checked_at TIMESTAMP(6),
    health_check_claimed_at TIMESTAMP(6),
    last_deployed_at TIMESTAMP(6),
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_application_code UNIQUE (code),
    CONSTRAINT fk_application_server FOREIGN KEY (server_id) REFERENCES server_node (id),
    CONSTRAINT fk_application_container FOREIGN KEY (container_snapshot_id) REFERENCES docker_container_snapshot (id) ON DELETE SET NULL,
    CONSTRAINT fk_application_creator FOREIGN KEY (created_by) REFERENCES sys_user (id)
);

CREATE INDEX idx_application_server ON application (server_id, environment, status);
CREATE INDEX idx_application_health_due ON application (server_id, health_check_claimed_at, health_checked_at);

CREATE TABLE application_deployment (
    id BIGINT NOT NULL,
    application_id BIGINT NOT NULL,
    version VARCHAR(120) NOT NULL,
    server_id BIGINT NOT NULL,
    docker_image VARCHAR(500) NOT NULL,
    operator_id BIGINT NOT NULL,
    deployed_at TIMESTAMP(6) NOT NULL,
    result VARCHAR(32) NOT NULL,
    logs TEXT,
    PRIMARY KEY (id),
    CONSTRAINT fk_application_deployment_application FOREIGN KEY (application_id) REFERENCES application (id) ON DELETE CASCADE,
    CONSTRAINT fk_application_deployment_server FOREIGN KEY (server_id) REFERENCES server_node (id),
    CONSTRAINT fk_application_deployment_operator FOREIGN KEY (operator_id) REFERENCES sys_user (id)
);

CREATE INDEX idx_application_deployment_recent ON application_deployment (deployed_at, application_id);
