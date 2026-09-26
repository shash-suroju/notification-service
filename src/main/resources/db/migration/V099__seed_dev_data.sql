-- This migration seeds dev/demo data. In production, skip this or use a separate profile.
-- Password for all users: "password123" (BCrypt hash below)

-- Platform admin
INSERT INTO app_user (id, username, password_hash, role, tenant_id) VALUES
    ('00000000-0000-0000-0000-000000000001', 'platform-admin',
     '$2a$10$VL5s5XVdcVdMhthdPQvQc.bi5qaQORYT2A/3Icas4CjpNzwoT9ZvS',
     'PLATFORM_ADMIN', NULL);

-- Tenant A: Acme Corp
INSERT INTO tenant (id, name, slug, status, rate_limit_per_sec, burst, weight, max_attempts) VALUES
    ('10000000-0000-0000-0000-000000000001', 'Acme Corp', 'acme', 'ACTIVE', 100, 200, 1, 5);

INSERT INTO app_user (id, username, password_hash, role, tenant_id) VALUES
    ('00000000-0000-0000-0000-000000000010', 'acme-admin',
     '$2a$10$VL5s5XVdcVdMhthdPQvQc.bi5qaQORYT2A/3Icas4CjpNzwoT9ZvS',
     'TENANT_ADMIN', '10000000-0000-0000-0000-000000000001');

INSERT INTO channel_config (id, tenant_id, channel, enabled) VALUES
    ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'EMAIL', true),
    ('20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 'SMS', true),
    ('20000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001', 'PUSH', true),
    ('20000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000001', 'IN_APP', true);

INSERT INTO template (id, tenant_id, code, channel, version, subject, body) VALUES
    ('30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
     'welcome', 'EMAIL', 1, 'Welcome to {{companyName}}!',
     'Hello {{name}}, welcome to {{companyName}}! Your account is ready.'),
    ('30000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001',
     'order_shipped', 'SMS', 1, NULL,
     'Hi {{name}}, your order {{orderId}} has shipped! Track: {{trackingUrl}}'),
    ('30000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001',
     'payment_received', 'PUSH', 1, 'Payment received',
     'We received your payment of {{amount}}. Thank you!');

-- Test API key for Acme (dev only):
--   raw key : ntfy_acme1234_testkey12345678901234567890ab
--   SHA-256 : 446b00489fd8a505102d4d1e06aba2022d4275a0bfd6d4b69a1d30132a07f1a4
INSERT INTO api_key (id, tenant_id, prefix, key_hash, name, status) VALUES
    ('40000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
     'acme1234', '446b00489fd8a505102d4d1e06aba2022d4275a0bfd6d4b69a1d30132a07f1a4', 'Test Key', 'ACTIVE');

-- Tenant B: Globex Inc
INSERT INTO tenant (id, name, slug, status, rate_limit_per_sec, burst, weight, max_attempts) VALUES
    ('10000000-0000-0000-0000-000000000002', 'Globex Inc', 'globex', 'ACTIVE', 50, 100, 1, 3);

INSERT INTO app_user (id, username, password_hash, role, tenant_id) VALUES
    ('00000000-0000-0000-0000-000000000020', 'globex-admin',
     '$2a$10$VL5s5XVdcVdMhthdPQvQc.bi5qaQORYT2A/3Icas4CjpNzwoT9ZvS',
     'TENANT_ADMIN', '10000000-0000-0000-0000-000000000002');

INSERT INTO channel_config (id, tenant_id, channel, enabled) VALUES
    ('20000000-0000-0000-0000-000000000010', '10000000-0000-0000-0000-000000000002', 'EMAIL', true),
    ('20000000-0000-0000-0000-000000000011', '10000000-0000-0000-0000-000000000002', 'SMS', false),
    ('20000000-0000-0000-0000-000000000012', '10000000-0000-0000-0000-000000000002', 'IN_APP', true);

INSERT INTO template (id, tenant_id, code, channel, version, subject, body) VALUES
    ('30000000-0000-0000-0000-000000000010', '10000000-0000-0000-0000-000000000002',
     'alert', 'EMAIL', 1, 'Alert: {{alertType}}',
     'Hello {{name}}, an alert of type {{alertType}} was triggered: {{message}}');
