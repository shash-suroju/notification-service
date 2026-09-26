CREATE TABLE global_channel_limit (
    channel         VARCHAR(20)  PRIMARY KEY,
    rate_per_sec    INT          NOT NULL DEFAULT 500,
    burst           INT          NOT NULL DEFAULT 1000,

    CONSTRAINT ck_gcl_channel CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'IN_APP')),
    CONSTRAINT ck_gcl_rate CHECK (rate_per_sec > 0),
    CONSTRAINT ck_gcl_burst CHECK (burst >= rate_per_sec)
);

-- Seed default global limits
INSERT INTO global_channel_limit (channel, rate_per_sec, burst) VALUES
    ('EMAIL',  500, 1000),
    ('SMS',    200, 400),
    ('PUSH',   500, 1000),
    ('IN_APP', 1000, 2000);
