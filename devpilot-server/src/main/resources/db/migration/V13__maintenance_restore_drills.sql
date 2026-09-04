CREATE TABLE maintenance_restore_drill (
    id BIGINT NOT NULL,
    backup_report_id BIGINT,
    environment VARCHAR(24) NOT NULL,
    result VARCHAR(24) NOT NULL,
    notes VARCHAR(1000),
    performed_by BIGINT,
    performed_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_restore_drill_backup FOREIGN KEY (backup_report_id)
        REFERENCES maintenance_backup_report (id) ON DELETE SET NULL,
    CONSTRAINT fk_restore_drill_user FOREIGN KEY (performed_by)
        REFERENCES sys_user (id) ON DELETE SET NULL
);

CREATE INDEX idx_restore_drill_recent ON maintenance_restore_drill (performed_at);
