CREATE TABLE alert_notification_route (
    id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    server_id BIGINT,
    minimum_severity VARCHAR(20) NOT NULL,
    webhook_url_encrypted TEXT NOT NULL,
    destination_type VARCHAR(32) NOT NULL,
    notify_resolved TINYINT NOT NULL DEFAULT 1,
    enabled TINYINT NOT NULL DEFAULT 1,
    quiet_enabled TINYINT NOT NULL DEFAULT 0,
    quiet_start VARCHAR(5),
    quiet_end VARCHAR(5),
    quiet_days VARCHAR(100),
    timezone VARCHAR(64) NOT NULL,
    critical_bypass_mute TINYINT NOT NULL DEFAULT 1,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_alert_notification_route_name UNIQUE (name),
    CONSTRAINT fk_alert_route_server FOREIGN KEY (server_id) REFERENCES server_node (id) ON DELETE CASCADE,
    CONSTRAINT fk_alert_route_creator FOREIGN KEY (created_by) REFERENCES sys_user (id) ON DELETE CASCADE
);

CREATE INDEX idx_alert_route_matching ON alert_notification_route (enabled, server_id, minimum_severity);

CREATE TABLE alert_maintenance_window (
    id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    reason VARCHAR(500),
    server_id BIGINT,
    starts_at TIMESTAMP(6) NOT NULL,
    ends_at TIMESTAMP(6) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_alert_maintenance_server FOREIGN KEY (server_id) REFERENCES server_node (id) ON DELETE CASCADE,
    CONSTRAINT fk_alert_maintenance_creator FOREIGN KEY (created_by) REFERENCES sys_user (id) ON DELETE CASCADE
);

CREATE INDEX idx_alert_maintenance_active ON alert_maintenance_window (starts_at, ends_at, server_id);

ALTER TABLE alert_notification ADD COLUMN route_id BIGINT;
ALTER TABLE alert_notification ADD COLUMN route_name VARCHAR(120);
ALTER TABLE alert_notification ADD CONSTRAINT fk_alert_notification_route
    FOREIGN KEY (route_id) REFERENCES alert_notification_route (id) ON DELETE SET NULL;
