CREATE TABLE channel_config (
    id              UUID PRIMARY KEY,
    tenant_id       UUID         NOT NULL,
    channel         VARCHAR(20)  NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    settings        JSONB        NOT NULL DEFAULT '{}',

    CONSTRAINT uq_channelconfig_tenant_channel UNIQUE (tenant_id, channel),
    CONSTRAINT ck_cc_channel CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'IN_APP')),
    CONSTRAINT fk_cc_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE
);
