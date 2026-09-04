ALTER TABLE docker_container_snapshot ADD COLUMN compose_project VARCHAR(255);
ALTER TABLE docker_container_snapshot ADD COLUMN compose_service VARCHAR(255);

CREATE INDEX idx_docker_container_compose
    ON docker_container_snapshot (server_id, active, compose_project);
