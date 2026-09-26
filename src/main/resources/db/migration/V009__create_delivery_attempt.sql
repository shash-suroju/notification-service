CREATE TABLE delivery_attempt (
    id                  UUID PRIMARY KEY,
    notification_id     UUID         NOT NULL,
    attempt_no          INT          NOT NULL,
    started_at          TIMESTAMPTZ  NOT NULL,
    finished_at         TIMESTAMPTZ,
    outcome             VARCHAR(30),
    error_code          VARCHAR(50),
    error_message       VARCHAR(500),
    provider_message_id VARCHAR(255),
    latency_ms          BIGINT,

    CONSTRAINT uq_attempt UNIQUE (notification_id, attempt_no),
    CONSTRAINT ck_attempt_outcome CHECK (outcome IS NULL OR outcome IN (
        'SUCCESS', 'TRANSIENT_FAILURE', 'PERMANENT_FAILURE', 'ABANDONED'
    )),
    CONSTRAINT fk_attempt_notif FOREIGN KEY (notification_id) REFERENCES notification(id) ON DELETE CASCADE
);
