CREATE TABLE sys_user (
    id BIGINT NOT NULL,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    email VARCHAR(190),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    failed_login_count INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMP(6),
    last_login_at TIMESTAMP(6),
    deleted TINYINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_user_username UNIQUE (username),
    CONSTRAINT uk_sys_user_email UNIQUE (email)
);

CREATE TABLE sys_role (
    id BIGINT NOT NULL,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_role_code UNIQUE (code)
);

CREATE TABLE sys_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES sys_role (id) ON DELETE CASCADE
);

CREATE TABLE auth_refresh_token (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    family_id CHAR(36) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6),
    replaced_by_hash CHAR(64),
    user_agent VARCHAR(512),
    ip_address VARCHAR(64),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE
);

CREATE INDEX idx_refresh_token_family ON auth_refresh_token (family_id);
CREATE INDEX idx_refresh_token_user ON auth_refresh_token (user_id);
CREATE INDEX idx_refresh_token_expiry ON auth_refresh_token (expires_at);

INSERT INTO sys_role (id, code, name, description)
SELECT 1, 'ADMIN', 'Administrator', 'Full platform administration'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE code = 'ADMIN');

INSERT INTO sys_role (id, code, name, description)
SELECT 2, 'DEVELOPER', 'Developer', 'Operate applications and inspect infrastructure'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE code = 'DEVELOPER');

INSERT INTO sys_role (id, code, name, description)
SELECT 3, 'VIEWER', 'Viewer', 'Read-only platform access'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE code = 'VIEWER');

