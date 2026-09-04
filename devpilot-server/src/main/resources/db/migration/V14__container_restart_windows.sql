ALTER TABLE docker_container_snapshot ADD COLUMN restart_window_started_at TIMESTAMP(6);
ALTER TABLE docker_container_snapshot ADD COLUMN restart_window_count INT NOT NULL DEFAULT 0;

CREATE INDEX idx_container_restart_window
    ON docker_container_snapshot (active, restart_window_started_at, restart_window_count);
