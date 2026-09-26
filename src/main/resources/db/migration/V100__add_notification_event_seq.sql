-- Total order for the audit trail.
--
-- occurred_at alone is not enough: several transitions can share one clock instant (a claim
-- of a SCHEDULED row writes SCHEDULED→PENDING and PENDING→PROCESSING together, and tests run
-- on a frozen clock). seq is assigned at insert and breaks those ties in insert order.
--
-- Numbered V100 rather than V012: V099 (seed data) is already applied in existing databases,
-- and Flyway rejects a migration numbered below the latest applied one.
ALTER TABLE notification_event ADD COLUMN seq BIGSERIAL;

CREATE INDEX idx_event_notification_seq ON notification_event (notification_id, occurred_at, seq);
