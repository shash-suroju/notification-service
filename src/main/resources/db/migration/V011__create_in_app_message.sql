CREATE TABLE in_app_message (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID         NOT NULL,
    recipient           VARCHAR(500) NOT NULL,
    notification_id     UUID         NOT NULL,
    title               VARCHAR(500),
    body                TEXT         NOT NULL,
    read_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_inapp_notification UNIQUE (notification_id),
    CONSTRAINT fk_inapp_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE,
    CONSTRAINT fk_inapp_notif FOREIGN KEY (notification_id) REFERENCES notification(id) ON DELETE CASCADE
);

CREATE INDEX idx_inapp_recipient ON in_app_message(tenant_id, recipient, created_at DESC);
