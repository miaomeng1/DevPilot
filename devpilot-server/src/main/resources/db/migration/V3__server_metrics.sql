CREATE TABLE server_metric (
    id BIGINT NOT NULL,
    server_id BIGINT NOT NULL,
    collected_at TIMESTAMP(6) NOT NULL,
    sample_count INT NOT NULL DEFAULT 1,
    cpu_usage DOUBLE NOT NULL,
    load_one DOUBLE NOT NULL,
    load_five DOUBLE NOT NULL,
    load_fifteen DOUBLE NOT NULL,
    memory_total BIGINT NOT NULL,
    memory_used BIGINT NOT NULL,
    memory_available BIGINT NOT NULL,
    disk_total BIGINT NOT NULL,
    disk_used BIGINT NOT NULL,
    disk_free BIGINT NOT NULL,
    network_bytes_sent BIGINT NOT NULL,
    network_bytes_received BIGINT NOT NULL,
    network_upload_rate DOUBLE NOT NULL,
    network_download_rate DOUBLE NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_server_metric_minute UNIQUE (server_id, collected_at),
    CONSTRAINT fk_metric_server FOREIGN KEY (server_id) REFERENCES server_node (id) ON DELETE CASCADE
);

CREATE INDEX idx_server_metric_time ON server_metric (collected_at);
CREATE INDEX idx_server_metric_server_time ON server_metric (server_id, collected_at);
