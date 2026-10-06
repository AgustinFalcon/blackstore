-- Forward-only, transactional. Verify an external backup before applying to an existing installation.
ALTER TABLE staff_users ADD COLUMN display_name VARCHAR(160);
UPDATE staff_users SET display_name = login;
ALTER TABLE staff_users ALTER COLUMN display_name SET NOT NULL;
ALTER TABLE staff_users ALTER COLUMN display_name SET DEFAULT 'Staff';
-- Disable only the historical fictitious credential, retaining every ID and FK.
UPDATE staff_users SET active = FALSE WHERE login = 'cashier' AND password_hash = '$2a$10$012345678901234567890u';
CREATE UNIQUE INDEX uq_open_cash_session_per_cashier ON cash_session_projection(cashier_id) WHERE status='OPEN';
CREATE TABLE staff_sessions (
 token_digest CHAR(64) PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES staff_users(id) ON DELETE RESTRICT,
 csrf_token VARCHAR(64) NOT NULL, created_at TIMESTAMPTZ NOT NULL, last_used_at TIMESTAMPTZ NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL, revoked_at TIMESTAMPTZ,
 CHECK(expires_at > created_at AND last_used_at >= created_at AND last_used_at <= expires_at)
);
CREATE TABLE staff_preauth_contexts (
 context_digest CHAR(64) PRIMARY KEY, csrf_digest CHAR(64) NOT NULL, origin_digest CHAR(64) NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX staff_preauth_expiry ON staff_preauth_contexts(expires_at);
CREATE INDEX staff_preauth_origin ON staff_preauth_contexts(origin_digest, expires_at);
CREATE TABLE staff_login_buckets (
 bucket_digest CHAR(64) PRIMARY KEY, failures INTEGER NOT NULL CHECK(failures >= 0),
 window_started_at TIMESTAMPTZ NOT NULL, blocked_until TIMESTAMPTZ
);
CREATE ROLE blackstore_staff_provisioner NOINHERIT;
GRANT SELECT ON staff_users, roles, cash_session_projection, sale_intents, sale_state_projection, payments TO blackstore_app;
GRANT SELECT, INSERT, UPDATE ON staff_sessions, staff_login_buckets TO blackstore_app;
GRANT SELECT, INSERT, DELETE ON staff_preauth_contexts TO blackstore_app;
GRANT SELECT, INSERT, UPDATE ON staff_users TO blackstore_staff_provisioner;
GRANT SELECT ON roles TO blackstore_staff_provisioner;
GRANT SELECT ON staff_sessions TO blackstore_staff_provisioner;
GRANT UPDATE (revoked_at) ON staff_sessions TO blackstore_staff_provisioner;
GRANT INSERT ON audit_events TO blackstore_staff_provisioner;
GRANT USAGE, SELECT ON SEQUENCE staff_users_id_seq, audit_events_id_seq TO blackstore_staff_provisioner;
