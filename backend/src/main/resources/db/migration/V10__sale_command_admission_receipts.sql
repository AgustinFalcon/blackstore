-- Forward-only: no receipt is invented for a legacy intention or outbox.
CREATE TABLE sale_command_admission_receipts (
 command_id UUID PRIMARY KEY,
 kind VARCHAR(16) NOT NULL CHECK (kind IN ('Reserve','Commit','Release')),
 payload_hash CHAR(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
 actor_id BIGINT NOT NULL REFERENCES staff_users(id) ON DELETE RESTRICT,
 cash_session_id BIGINT NOT NULL REFERENCES cash_session_projection(id) ON DELETE RESTRICT,
 client_instance_id UUID NOT NULL,
 device_id VARCHAR(80) NOT NULL,
 sale_id VARCHAR(80) NOT NULL,
 operation_id UUID NOT NULL,
 intent_id BIGINT NOT NULL REFERENCES sale_intents(id) ON DELETE RESTRICT,
 outbox_id BIGINT NOT NULL UNIQUE REFERENCES storecore_outbox_commands(id) ON DELETE RESTRICT,
 accepted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE (client_instance_id,device_id,sale_id,operation_id,kind)
);

CREATE FUNCTION validate_sale_admission_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS (
  SELECT 1 FROM sale_intents i JOIN storecore_outbox_commands o
   ON o.client_instance_id=i.client_instance_id AND o.device_id=i.device_id AND o.sale_id=i.sale_id AND o.operation_id=i.operation_id
  WHERE i.id=NEW.intent_id AND o.id=NEW.outbox_id
   AND i.client_instance_id=NEW.client_instance_id AND i.device_id=NEW.device_id AND i.sale_id=NEW.sale_id AND i.operation_id=NEW.operation_id
   AND i.cash_session_id=NEW.cash_session_id AND o.cash_session_id=NEW.cash_session_id AND o.actor_id=NEW.actor_id
   AND o.operation_kind=upper(NEW.kind) AND o.canonical_payload IS NOT NULL AND o.payload_hash IS NOT NULL
   AND i.canonical_path=o.canonical_path AND i.contract_version=o.contract_version AND i.openapi_digest=o.openapi_digest
   AND o.canonical_payload->>'clientInstanceId'=NEW.client_instance_id::text
   AND o.canonical_payload->>'deviceId'=NEW.device_id AND o.canonical_payload->>'saleId'=NEW.sale_id
   AND o.canonical_payload->>'operationId'=NEW.operation_id::text AND o.canonical_payload->>'kind'=o.operation_kind
   AND o.canonical_payload->>'canonicalPath'=o.canonical_path AND o.canonical_payload->>'contractVersion'=o.contract_version
   AND o.canonical_payload->>'openapiDigest'=o.openapi_digest
 ) THEN RAISE EXCEPTION 'sale admission evidence mismatch'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER sale_admission_evidence BEFORE INSERT ON sale_command_admission_receipts FOR EACH ROW EXECUTE FUNCTION validate_sale_admission_receipt();
CREATE TRIGGER sale_admission_active BEFORE INSERT ON sale_command_admission_receipts FOR EACH ROW EXECUTE FUNCTION require_active_accounting();
CREATE TRIGGER immutable_sale_admission BEFORE UPDATE OR DELETE ON sale_command_admission_receipts FOR EACH ROW EXECUTE FUNCTION reject_historical_mutation();
REVOKE ALL ON sale_command_admission_receipts FROM PUBLIC,blackstore_app,blackstore_projection_worker,blackstore_outbox_worker;
GRANT SELECT,INSERT ON sale_command_admission_receipts TO blackstore_app;
GRANT SELECT ON sale_command_admission_receipts TO blackstore_auditor;
