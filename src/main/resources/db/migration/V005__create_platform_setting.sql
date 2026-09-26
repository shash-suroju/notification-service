CREATE TABLE platform_setting (
    key             VARCHAR(100) PRIMARY KEY,
    value           VARCHAR(500) NOT NULL
);

INSERT INTO platform_setting (key, value) VALUES
    ('max_tenant_rate_per_sec', '1000'),
    ('default_max_attempts', '5');
