CREATE TABLE tenant (
    id              UUID PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    slug            VARCHAR(100) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    rate_limit_per_sec  INT      NOT NULL DEFAULT 100,
    burst           INT          NOT NULL DEFAULT 200,
    weight          INT          NOT NULL DEFAULT 1,
    max_attempts    INT          NOT NULL DEFAULT 5,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uq_tenant_name UNIQUE (name),
    CONSTRAINT uq_tenant_slug UNIQUE (slug),
    CONSTRAINT ck_tenant_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT ck_tenant_rate CHECK (rate_limit_per_sec > 0),
    CONSTRAINT ck_tenant_burst CHECK (burst >= rate_limit_per_sec),
    CONSTRAINT ck_tenant_weight CHECK (weight BETWEEN 1 AND 10),
    CONSTRAINT ck_tenant_attempts CHECK (max_attempts BETWEEN 1 AND 20)
);
