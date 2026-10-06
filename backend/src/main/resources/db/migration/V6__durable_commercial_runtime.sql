ALTER TABLE sale_state_projection ADD COLUMN version BIGINT NOT NULL DEFAULT 0 CHECK(version >= 0);
ALTER TABLE storecore_outbox_commands ADD COLUMN canonical_payload JSONB,
 ADD COLUMN payload_hash CHAR(64), ADD COLUMN actor_id BIGINT REFERENCES staff_users(id),
 ADD COLUMN cash_session_id BIGINT REFERENCES cash_session_projection(id), ADD COLUMN business_reason VARCHAR(500),
 ADD CONSTRAINT complete_dispatch_payload CHECK ((canonical_payload IS NULL AND payload_hash IS NULL) OR
 (jsonb_typeof(canonical_payload)='object' AND payload_hash ~ '^[0-9a-f]{64}$' AND actor_id IS NOT NULL AND cash_session_id IS NOT NULL));
ALTER TABLE storecore_inbox_events ADD COLUMN evidence_json JSONB, ADD COLUMN evidence_hash CHAR(64),
 ADD CONSTRAINT complete_remote_evidence CHECK ((evidence_json IS NULL AND evidence_hash IS NULL) OR
 (jsonb_typeof(evidence_json)='object' AND evidence_hash ~ '^[0-9a-f]{64}$'));
CREATE TABLE storecore_command_delivery (
 command_id BIGINT PRIMARY KEY REFERENCES storecore_outbox_commands(id),
 state VARCHAR(32) NOT NULL CHECK(state IN ('PENDING','IN_FLIGHT','UNCERTAIN','APPLIED','RECONCILIATION_REQUIRED','LEGACY_INCOMPLETE')),
 claim_token UUID, claim_epoch BIGINT NOT NULL DEFAULT 0 CHECK(claim_epoch>=0),
 lease_until TIMESTAMPTZ, next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(), attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts>=0),
 last_error_code VARCHAR(64), updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
INSERT INTO storecore_command_delivery(command_id,state) SELECT id,'LEGACY_INCOMPLETE' FROM storecore_outbox_commands;
CREATE INDEX command_delivery_claim ON storecore_command_delivery(next_attempt_at,command_id) WHERE state IN ('PENDING','UNCERTAIN','IN_FLIGHT');
CREATE TABLE storecore_inbox_applications (
 inbox_id BIGINT NOT NULL REFERENCES storecore_inbox_events(id), command_id BIGINT NOT NULL REFERENCES storecore_outbox_commands(id),
 claim_token UUID NOT NULL, state VARCHAR(24) NOT NULL CHECK(state IN ('APPLIED','LATE_IGNORED')),
 applied_at TIMESTAMPTZ NOT NULL DEFAULT now(), late_reason VARCHAR(64), PRIMARY KEY(inbox_id,command_id,claim_token));
CREATE OR REPLACE FUNCTION enforce_sale_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status = NEW.status THEN
  -- Generated net_sales is not populated in NEW during a BEFORE trigger; its inputs are compared.
  IF (to_jsonb(OLD)-'version'-'updated_at'-'net_sales') IS DISTINCT FROM (to_jsonb(NEW)-'version'-'updated_at'-'net_sales')
   OR NEW.version <> OLD.version+1 OR NEW.updated_at < OLD.updated_at THEN
   RAISE EXCEPTION 'same-state update may only advance version and updated_at';
  END IF;
 ELSIF NOT ((OLD.status='PENDING_RESERVATION' AND NEW.status IN ('RESERVED','RELEASE_PENDING','RECONCILIATION_REQUIRED'))
 OR (OLD.status='RESERVED' AND NEW.status IN ('PAYMENT_CAPTURED','RELEASE_PENDING','RECONCILIATION_REQUIRED'))
 OR (OLD.status='PAYMENT_CAPTURED' AND NEW.status IN ('COMMIT_PENDING','RELEASE_PENDING','RECONCILIATION_REQUIRED'))
 OR (OLD.status='COMMIT_PENDING' AND NEW.status IN ('COMMITTED','RECONCILIATION_REQUIRED'))
 OR (OLD.status='RELEASE_PENDING' AND NEW.status IN ('RELEASED','RECONCILIATION_REQUIRED'))) THEN
  RAISE EXCEPTION 'invalid sale transition';
 END IF;
 IF (OLD.client_instance_id,OLD.device_id,OLD.sale_id,OLD.operation_id,OLD.sale_intent_id,OLD.aggregate_operation_key)
 IS DISTINCT FROM (NEW.client_instance_id,NEW.device_id,NEW.sale_id,NEW.operation_id,NEW.sale_intent_id,NEW.aggregate_operation_key) THEN
  RAISE EXCEPTION 'sale identity is immutable';
 END IF;
 RETURN NEW;
END; $$;
GRANT SELECT,INSERT,UPDATE ON storecore_command_delivery TO blackstore_app;
GRANT SELECT,INSERT ON storecore_inbox_applications TO blackstore_app;
GRANT SELECT,INSERT ON outbox_delivery_attempts TO blackstore_app;
GRANT USAGE,SELECT ON SEQUENCE outbox_delivery_attempts_id_seq TO blackstore_app;
GRANT UPDATE ON sale_state_projection TO blackstore_app;
GRANT SELECT ON staff_users,cash_session_projection TO blackstore_app;
GRANT SELECT ON storecore_outbox_commands,storecore_inbox_events TO blackstore_outbox_worker;
GRANT SELECT,UPDATE ON storecore_command_delivery TO blackstore_outbox_worker;
GRANT SELECT ON storecore_command_delivery,storecore_inbox_applications TO blackstore_projection_worker;
REVOKE UPDATE,DELETE ON storecore_outbox_commands,storecore_inbox_events FROM blackstore_outbox_worker,blackstore_projection_worker;
