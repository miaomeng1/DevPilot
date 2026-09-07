CREATE TABLE platform_setup (
    id INT PRIMARY KEY,
    public_url VARCHAR(1000),
    provider_url VARCHAR(1000),
    provider_token_cipher LONGTEXT,
    server_id BIGINT,
    revision VARCHAR(36) NOT NULL,
    provider_verified_at TIMESTAMP NULL,
    provider_error VARCHAR(1000)
);
INSERT INTO platform_setup(id, revision) VALUES (1, 'initial');
