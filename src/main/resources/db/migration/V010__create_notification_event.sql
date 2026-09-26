CREATE TABLE notification_event (
    id                  UUID PRIMARY KEY,
    notification_id     UUID         NOT NULL,
    tenant_id           UUID         NOT NULL,
    from_status         VARCHAR(20),
    to_status           VARCHAR(20)  NOT NULL,
    reason              VARCHAR(255),
    actor               VARCHAR(20)  NOT NULL,
    occurred_at         TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ck_event_actor CHECK (actor IN ('API', 'DISPATCHER', 'REAPER', 'ADMIN')),
    CONSTRAINT fk_event_notif FOREIGN KEY (notification_id) REFERENCES notification(id) ON DELETE CASCADE
);

CREATE INDEX idx_event_notification ON notification_event(notification_id, occurred_at);
CREATE INDEX idx_event_tenant ON notification_event(tenant_id, occurred_at);
