CREATE TABLE api_key (
    id              UUID PRIMARY KEY,
    tenant_id       UUID         NOT NULL,
    prefix          VARCHAR(8)   NOT NULL,
    key_hash        VARCHAR(64)  NOT NULL,
    name            VARCHAR(100),
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_used_at    TIMESTAMPTZ,

    CONSTRAINT ck_apikey_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT fk_apikey_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE
);

CREATE INDEX idx_apikey_prefix ON api_key(prefix);
CREATE INDEX idx_apikey_tenant ON api_key(tenant_id);
