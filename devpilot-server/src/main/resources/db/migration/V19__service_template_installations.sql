CREATE TABLE service_installation (
    id BIGINT NOT NULL,
    template_id VARCHAR(64) NOT NULL,
    template_name VARCHAR(120) NOT NULL,
    image VARCHAR(500) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    instance_name VARCHAR(64) NOT NULL,
    environment VARCHAR(32) NOT NULL,
    server_id BIGINT NOT NULL,
    requested_port INT NOT NULL,
    host_port INT,
    timezone VARCHAR(64) NOT NULL,
    container_id VARCHAR(64),
    application_id BIGINT,
    status VARCHAR(32) NOT NULL DEFAULT 'REQUESTED',
    error_message VARCHAR(1000),
    requested_by BIGINT NOT NULL,
    requested_at TIMESTAMP(6) NOT NULL,
    claimed_at TIMESTAMP(6),
    completed_at TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_service_install_server FOREIGN KEY (server_id) REFERENCES server_node (id) ON DELETE CASCADE,
    CONSTRAINT fk_service_install_application FOREIGN KEY (application_id) REFERENCES application (id) ON DELETE SET NULL,
    CONSTRAINT fk_service_install_user FOREIGN KEY (requested_by) REFERENCES sys_user (id) ON DELETE CASCADE
);

CREATE INDEX idx_service_install_queue ON service_installation (server_id, status, requested_at);
CREATE INDEX idx_service_install_instance ON service_installation (server_id, instance_name, status);
