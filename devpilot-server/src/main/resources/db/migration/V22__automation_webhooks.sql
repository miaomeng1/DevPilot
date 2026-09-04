CREATE TABLE automation_webhook_subscription (
    id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    endpoint_url_encrypted TEXT NOT NULL,
    endpoint_host VARCHAR(255) NOT NULL,
    secret_encrypted TEXT NOT NULL,
    event_types VARCHAR(500) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_automation_webhook_name UNIQUE (name),
    CONSTRAINT fk_automation_webhook_creator FOREIGN KEY (created_by) REFERENCES sys_user (id) ON DELETE CASCADE
);

CREATE TABLE automation_webhook_delivery (
    id BIGINT NOT NULL,
    event_id CHAR(36) NOT NULL,
    subscription_id BIGINT,
    subscription_name VARCHAR(120) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    response_code INT,
    error_message VARCHAR(1000),
    next_attempt_at TIMESTAMP(6) NOT NULL,
    sent_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_automation_delivery_subscription FOREIGN KEY (subscription_id)
        REFERENCES automation_webhook_subscription (id) ON DELETE SET NULL
);

CREATE INDEX idx_automation_delivery_due ON automation_webhook_delivery (status, next_attempt_at, attempt_count);
CREATE INDEX idx_automation_delivery_event ON automation_webhook_delivery (event_id);
