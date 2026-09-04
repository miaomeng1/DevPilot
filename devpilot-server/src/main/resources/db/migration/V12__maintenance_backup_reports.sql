CREATE TABLE maintenance_backup_report (
    id BIGINT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    destination_type VARCHAR(24) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    verified_at TIMESTAMP(6) NOT NULL,
    reported_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_maintenance_backup_sha256 UNIQUE (sha256)
);

CREATE INDEX idx_maintenance_backup_created ON maintenance_backup_report (created_at);
