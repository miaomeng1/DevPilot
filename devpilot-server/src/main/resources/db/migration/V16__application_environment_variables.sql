CREATE TABLE application_environment_state (
    application_id BIGINT NOT NULL,
    revision INT NOT NULL DEFAULT 0,
    synced_revision INT,
    last_synced_keys_json TEXT,
    sync_status VARCHAR(32) NOT NULL DEFAULT 'NOT_SYNCED',
    sync_error VARCHAR(1000),
    provider_synced_at TIMESTAMP(6),
    updated_by BIGINT,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (application_id),
    CONSTRAINT fk_environment_state_application FOREIGN KEY (application_id) REFERENCES application (id) ON DELETE CASCADE,
    CONSTRAINT fk_environment_state_user FOREIGN KEY (updated_by) REFERENCES sys_user (id) ON DELETE SET NULL
);

CREATE TABLE application_environment_variable (
    id BIGINT NOT NULL,
    application_id BIGINT NOT NULL,
    variable_key VARCHAR(128) NOT NULL,
    value_cipher TEXT NOT NULL,
    secret TINYINT NOT NULL DEFAULT 0,
    description VARCHAR(255),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_environment_variable UNIQUE (application_id, variable_key),
    CONSTRAINT fk_environment_variable_application FOREIGN KEY (application_id) REFERENCES application (id) ON DELETE CASCADE
);

CREATE INDEX idx_environment_variable_application
    ON application_environment_variable (application_id, variable_key);
