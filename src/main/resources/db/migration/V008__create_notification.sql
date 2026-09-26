CREATE TABLE notification (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID         NOT NULL,
    channel             VARCHAR(20)  NOT NULL,
    recipient           VARCHAR(500) NOT NULL,

    -- idempotency
    idempotency_key     VARCHAR(255) NOT NULL,
    request_hash        VARCHAR(64)  NOT NULL,

    -- template snapshot
    template_id         UUID,
    template_version    INT,
    subject             VARCHAR(500),
    body                TEXT         NOT NULL,
    variables           JSONB,

    -- state
    status              VARCHAR(20)  NOT NULL,

    -- scheduling + queue
    scheduled_at        TIMESTAMPTZ,
    next_attempt_at     TIMESTAMPTZ  NOT NULL,

    -- retry
    attempt_count       INT          NOT NULL DEFAULT 0,
    max_attempts        INT          NOT NULL DEFAULT 5,

    -- lease
    locked_by           VARCHAR(50),
    locked_until        TIMESTAMPTZ,

    -- outcome
    last_error_code     VARCHAR(50),
    failure_reason      VARCHAR(500),
    sent_at             TIMESTAMPTZ,

    -- audit
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_notif_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT ck_notif_channel CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'IN_APP')),
    CONSTRAINT ck_notif_status CHECK (status IN (
        'SCHEDULED', 'PENDING', 'PROCESSING', 'RETRYING', 'SENT', 'FAILED', 'CANCELLED'
    )),
    CONSTRAINT ck_notif_attempts CHECK (attempt_count >= 0),
    CONSTRAINT fk_notif_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT fk_notif_template FOREIGN KEY (template_id) REFERENCES template(id)
);

-- THE critical index: only rows eligible for claiming are indexed
CREATE INDEX idx_notification_claim
    ON notification (tenant_id, channel, next_attempt_at)
    WHERE status IN ('SCHEDULED', 'PENDING', 'RETRYING');

-- For the lease reaper
CREATE INDEX idx_notification_lease
    ON notification (status, locked_until)
    WHERE status = 'PROCESSING';

-- For delivery reports
CREATE INDEX idx_notification_tenant_status
    ON notification (tenant_id, status, created_at);
