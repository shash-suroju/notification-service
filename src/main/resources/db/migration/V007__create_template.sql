CREATE TABLE template (
    id              UUID PRIMARY KEY,
    tenant_id       UUID         NOT NULL,
    code            VARCHAR(100) NOT NULL,
    channel         VARCHAR(20)  NOT NULL,
    version         INT          NOT NULL DEFAULT 1,
    subject         VARCHAR(500),
    body            TEXT         NOT NULL,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_template_version UNIQUE (tenant_id, code, channel, version),
    CONSTRAINT ck_tmpl_channel CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'IN_APP')),
    CONSTRAINT fk_tmpl_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE
);

CREATE INDEX idx_template_lookup ON template(tenant_id, code, channel, active);
