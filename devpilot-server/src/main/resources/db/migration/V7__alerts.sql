CREATE TABLE system_setting (
    id BIGINT NOT NULL,
    setting_key VARCHAR(100) NOT NULL,
    setting_value TEXT,
    sensitive_value TINYINT NOT NULL DEFAULT 0,
    updated_by BIGINT,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_system_setting_key UNIQUE (setting_key),
    CONSTRAINT fk_system_setting_user FOREIGN KEY (updated_by) REFERENCES sys_user (id) ON DELETE SET NULL
);

CREATE TABLE alert_rule (
    id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    metric_type VARCHAR(40) NOT NULL,
    operator VARCHAR(20) NOT NULL,
    threshold DOUBLE,
    duration_seconds INT NOT NULL DEFAULT 0,
    severity VARCHAR(20) NOT NULL,
    server_id BIGINT,
    enabled TINYINT NOT NULL DEFAULT 1,
    deleted TINYINT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_alert_rule_server FOREIGN KEY (server_id) REFERENCES server_node (id),
    CONSTRAINT fk_alert_rule_creator FOREIGN KEY (created_by) REFERENCES sys_user (id)
);

CREATE INDEX idx_alert_rule_enabled ON alert_rule (deleted, enabled, metric_type, server_id);

CREATE TABLE alert_event (
    id BIGINT NOT NULL,
    rule_id BIGINT NOT NULL,
    server_id BIGINT NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id VARCHAR(100) NOT NULL,
    resource_name VARCHAR(255) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    status VARCHAR(24) NOT NULL,
    current_value DOUBLE,
    started_at TIMESTAMP(6) NOT NULL,
    acknowledged_by BIGINT,
    acknowledged_at TIMESTAMP(6),
    resolved_at TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_alert_event_rule FOREIGN KEY (rule_id) REFERENCES alert_rule (id),
    CONSTRAINT fk_alert_event_server FOREIGN KEY (server_id) REFERENCES server_node (id),
    CONSTRAINT fk_alert_event_ack_user FOREIGN KEY (acknowledged_by) REFERENCES sys_user (id) ON DELETE SET NULL
);

CREATE INDEX idx_alert_event_status ON alert_event (status, severity, started_at);
CREATE INDEX idx_alert_event_resource ON alert_event (rule_id, resource_type, resource_id, status);

CREATE TABLE alert_condition_state (
    id BIGINT NOT NULL,
    rule_id BIGINT NOT NULL,
    server_id BIGINT NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id VARCHAR(100) NOT NULL,
    resource_name VARCHAR(255) NOT NULL,
    current_value DOUBLE,
    first_met_at TIMESTAMP(6) NOT NULL,
    last_observed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_alert_condition_resource UNIQUE (rule_id, resource_type, resource_id),
    CONSTRAINT fk_alert_condition_rule FOREIGN KEY (rule_id) REFERENCES alert_rule (id) ON DELETE CASCADE,
    CONSTRAINT fk_alert_condition_server FOREIGN KEY (server_id) REFERENCES server_node (id) ON DELETE CASCADE
);

CREATE TABLE alert_notification (
    id BIGINT NOT NULL,
    event_id BIGINT NOT NULL,
    transition_type VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    response_code INT,
    error_message VARCHAR(1000),
    next_attempt_at TIMESTAMP(6) NOT NULL,
    sent_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_alert_notification_event FOREIGN KEY (event_id) REFERENCES alert_event (id) ON DELETE CASCADE
);

CREATE INDEX idx_alert_notification_delivery ON alert_notification (status, next_attempt_at);
