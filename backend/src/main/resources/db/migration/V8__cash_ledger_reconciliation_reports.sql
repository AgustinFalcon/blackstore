-- Additive rollout only: legacy facts are preserved without retrospective attribution.
DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM cash_ledger_events WHERE event_type='OPENING' GROUP BY cash_session_id HAVING count(*)>1) THEN
  RAISE EXCEPTION 'V8 preflight: duplicate historical OPENING events; resolve evidence before migration';
 END IF;
END $$;

CREATE TABLE accounting_runtime (
 singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK(singleton),
 state VARCHAR(24) NOT NULL CHECK(state IN ('PRE_ACTIVATION','ACTIVE','PAUSED')),
 accounting_activation_at TIMESTAMPTZ,
 changed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 CHECK ((state='PRE_ACTIVATION' AND accounting_activation_at IS NULL) OR
        (state IN ('ACTIVE','PAUSED') AND accounting_activation_at IS NOT NULL))
);
INSERT INTO accounting_runtime(state) VALUES ('PRE_ACTIVATION');
CREATE FUNCTION enforce_accounting_lifecycle() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'accounting lifecycle cannot be deleted'; END IF;
 IF OLD.accounting_activation_at IS NOT NULL AND
    (NEW.state='PRE_ACTIVATION' OR NEW.accounting_activation_at IS DISTINCT FROM OLD.accounting_activation_at) THEN
  RAISE EXCEPTION 'accounting activation is irreversible';
 END IF;
 IF OLD.state='PRE_ACTIVATION' AND NEW.state<>'ACTIVE' THEN
  RAISE EXCEPTION 'first accounting transition must activate';
 END IF;
 NEW.changed_at := clock_timestamp();
 RETURN NEW;
END $$;
CREATE TRIGGER accounting_lifecycle BEFORE UPDATE OR DELETE ON accounting_runtime
 FOR EACH ROW EXECUTE FUNCTION enforce_accounting_lifecycle();

CREATE TABLE accounting_command_receipts (
 command_id UUID PRIMARY KEY,
 actor_id BIGINT NOT NULL REFERENCES staff_users(id),
 command_kind VARCHAR(32) NOT NULL CHECK(command_kind IN
  ('CASH_SESSION_OPEN','CASH_SESSION_CLOSE','EXPENSE_RECORD','PAYMENT_CAPTURE','PAYMENT_REVERSE','FEE_RECORD','ADJUSTMENT_RECORD','COMMERCIAL_RECOGNITION')),
 payload_hash CHAR(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
 cash_session_id BIGINT NOT NULL REFERENCES cash_session_projection(id),
 sale_id BIGINT REFERENCES sale_state_projection(id),
 outcome VARCHAR(16) NOT NULL CHECK(outcome='COMMITTED'),
 result JSONB NOT NULL CHECK(jsonb_typeof(result)='object'),
 accounting_version INTEGER NOT NULL CHECK(accounting_version=2),
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
ALTER TABLE payments
 ADD COLUMN original_payment_id BIGINT REFERENCES payments(id) ON DELETE RESTRICT,
 ADD COLUMN accounting_command_id UUID REFERENCES accounting_command_receipts(command_id) DEFERRABLE INITIALLY DEFERRED,
 ADD COLUMN accounting_version INTEGER,
 ADD CONSTRAINT payment_v2_shape CHECK (
  (accounting_version IS NULL AND original_payment_id IS NULL AND accounting_command_id IS NULL) OR
  (accounting_version IS NOT NULL AND accounting_version=2 AND accounting_command_id IS NOT NULL AND
   ((status='CAPTURED' AND original_payment_id IS NULL) OR (status='REFUNDED' AND original_payment_id IS NOT NULL)))
 );
CREATE UNIQUE INDEX payment_one_full_refund ON payments(original_payment_id)
 WHERE accounting_version=2 AND status='REFUNDED';
CREATE UNIQUE INDEX payment_one_per_accounting_command ON payments(accounting_command_id)
 WHERE accounting_version=2;
CREATE FUNCTION enforce_accounting_payment_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE runtime_state VARCHAR(24); original payments%ROWTYPE; receipt accounting_command_receipts%ROWTYPE; BEGIN
 SELECT state INTO runtime_state FROM accounting_runtime WHERE singleton;
 IF (NEW.accounting_version IS NULL AND runtime_state<>'PRE_ACTIVATION') OR
    (NEW.accounting_version=2 AND runtime_state<>'ACTIVE') OR runtime_state IS NULL THEN
  RAISE EXCEPTION 'payment writer incompatible with accounting lifecycle';
 END IF;
 IF NEW.accounting_version=2 THEN
  SELECT * INTO receipt FROM accounting_command_receipts WHERE command_id=NEW.accounting_command_id;
  IF receipt.command_id IS NULL OR receipt.sale_id IS DISTINCT FROM NEW.sale_id OR
     (NEW.status='CAPTURED' AND receipt.command_kind<>'PAYMENT_CAPTURE') OR
     (NEW.status='REFUNDED' AND receipt.command_kind<>'PAYMENT_REVERSE') THEN
   RAISE EXCEPTION 'payment does not match its accounting command';
  END IF;
  IF NEW.status='REFUNDED' THEN
   SELECT * INTO original FROM payments WHERE id=NEW.original_payment_id;
   IF original.id IS NULL OR original.accounting_version IS DISTINCT FROM 2 OR original.status IS DISTINCT FROM 'CAPTURED'
    OR (original.sale_id,original.payment_method,original.amount) IS DISTINCT FROM (NEW.sale_id,NEW.payment_method,NEW.amount) THEN
    RAISE EXCEPTION 'refund must reference one complete captured payment';
   END IF;
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER accounting_payment_insert BEFORE INSERT ON payments
 FOR EACH ROW EXECUTE FUNCTION enforce_accounting_payment_insert();
CREATE TABLE cash_accounting_coverage (
 cash_session_id BIGINT PRIMARY KEY REFERENCES cash_session_projection(id),
 coverage VARCHAR(32) NOT NULL CHECK(coverage IN ('COMPLETE_FROM_OPENING','LEGACY_INCOMPLETE')),
 command_id UUID REFERENCES accounting_command_receipts(command_id) DEFERRABLE INITIALLY DEFERRED,
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 CHECK(coverage<>'COMPLETE_FROM_OPENING' OR command_id IS NOT NULL)
);
CREATE TABLE expense_settlements (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 expense_id BIGINT NOT NULL UNIQUE REFERENCES expenses(id),
 cash_session_id BIGINT NOT NULL REFERENCES cash_session_projection(id),
 payment_method VARCHAR(24) NOT NULL CHECK(payment_method IN ('CASH','CARD','TRANSFER','OTHER')),
 amount NUMERIC(14,2) NOT NULL CHECK(amount>0),
 actor_id BIGINT NOT NULL REFERENCES staff_users(id),
 command_id UUID NOT NULL REFERENCES accounting_command_receipts(command_id) DEFERRABLE INITIALLY DEFERRED,
 paid_at TIMESTAMPTZ NOT NULL,
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE FUNCTION enforce_expense_settlement() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM expenses WHERE id=NEW.expense_id AND amount=NEW.amount
   AND cash_session_id=NEW.cash_session_id AND paid_at IS NULL) THEN
  RAISE EXCEPTION 'settlement must pay the total of an unsettled expense in its cash session';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER expense_settlement_total BEFORE INSERT ON expense_settlements
 FOR EACH ROW EXECUTE FUNCTION enforce_expense_settlement();
CREATE TABLE cash_reconciliations (
 cash_session_id BIGINT PRIMARY KEY REFERENCES cash_session_projection(id),
 command_id UUID NOT NULL UNIQUE REFERENCES accounting_command_receipts(command_id) DEFERRABLE INITIALLY DEFERRED,
 actor_id BIGINT NOT NULL REFERENCES staff_users(id),
 declared_cash NUMERIC(14,2) NOT NULL CHECK(declared_cash>=0),
 expected_cash NUMERIC(14,2), difference NUMERIC(14,2),
 outcome VARCHAR(24) NOT NULL CHECK(outcome IN ('BALANCED','SHORTAGE','OVERAGE','UNAVAILABLE')),
 coverage VARCHAR(32) NOT NULL CHECK(coverage IN ('COMPLETE_FROM_OPENING','LEGACY_INCOMPLETE')),
 cutoff TIMESTAMPTZ NOT NULL, local_watermark BIGINT NOT NULL CHECK(local_watermark>=0),
 accounting_version INTEGER NOT NULL CHECK(accounting_version=2),
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 CHECK((outcome='UNAVAILABLE' AND coverage='LEGACY_INCOMPLETE' AND expected_cash IS NULL AND difference IS NULL) OR
  (coverage='COMPLETE_FROM_OPENING' AND expected_cash IS NOT NULL AND difference IS NOT NULL AND difference=declared_cash-expected_cash AND
   ((outcome='BALANCED' AND difference=0) OR (outcome='SHORTAGE' AND difference<0) OR (outcome='OVERAGE' AND difference>0))))
);
CREATE TABLE commercial_recognitions (
 sale_id BIGINT PRIMARY KEY REFERENCES sale_state_projection(id),
 cash_session_id BIGINT NOT NULL REFERENCES cash_session_projection(id),
 command_id UUID NOT NULL REFERENCES accounting_command_receipts(command_id) DEFERRABLE INITIALLY DEFERRED,
 actor_id BIGINT NOT NULL REFERENCES staff_users(id),
 gross_sales NUMERIC(14,2) NOT NULL CHECK(gross_sales>=0),
 discounts NUMERIC(14,2) NOT NULL CHECK(discounts>=0 AND discounts<=gross_sales),
 net_sales NUMERIC(14,2) GENERATED ALWAYS AS (gross_sales-discounts) STORED,
 occurred_at TIMESTAMPTZ NOT NULL,
 accounting_version INTEGER NOT NULL CHECK(accounting_version=2),
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE FUNCTION enforce_cash_reconciliation_close() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM cash_session_projection WHERE id=NEW.cash_session_id
  AND status IN ('CLOSED','RECONCILIATION_REQUIRED') AND closing_cash_declared=NEW.declared_cash AND closed_at=NEW.cutoff) THEN
  RAISE EXCEPTION 'reconciliation requires its durable close and declaration';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM cash_accounting_coverage WHERE cash_session_id=NEW.cash_session_id AND coverage=NEW.coverage) OR
  NEW.local_watermark<>(SELECT coalesce(max(local_sequence),0) FROM cash_ledger_events WHERE cash_session_id=NEW.cash_session_id AND accounting_version=2) THEN
  RAISE EXCEPTION 'reconciliation coverage or local watermark mismatch';
 END IF;
 RETURN NEW;
END $$;
-- Resolve this function's ledger columns when invoked, after the additive ALTER below.
CREATE TRIGGER cash_reconciliation_closed BEFORE INSERT ON cash_reconciliations
 FOR EACH ROW EXECUTE FUNCTION enforce_cash_reconciliation_close();
CREATE FUNCTION enforce_commercial_recognition() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM sale_state_projection s JOIN sale_intents i ON i.id=s.sale_intent_id
  WHERE s.id=NEW.sale_id AND s.status='COMMITTED' AND i.cash_session_id=NEW.cash_session_id
   AND s.gross_sales=NEW.gross_sales AND s.discounts=NEW.discounts) THEN
  RAISE EXCEPTION 'recognition requires the committed sale and its authorized snapshot';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER commercial_recognition_committed BEFORE INSERT ON commercial_recognitions
 FOR EACH ROW EXECUTE FUNCTION enforce_commercial_recognition();
CREATE TABLE accounting_zone_versions (
 version VARCHAR(32) PRIMARY KEY CHECK(length(btrim(version))>0),
 zone_id VARCHAR(100) NOT NULL,
 effective_at TIMESTAMPTZ NOT NULL UNIQUE,
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE FUNCTION enforce_accounting_zone() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_timezone_names WHERE name=NEW.zone_id) THEN
  RAISE EXCEPTION 'accounting zone must be a PostgreSQL IANA time zone';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER accounting_zone_valid BEFORE INSERT ON accounting_zone_versions
 FOR EACH ROW EXECUTE FUNCTION enforce_accounting_zone();

ALTER TABLE cash_ledger_events
 ADD COLUMN payment_method VARCHAR(24),
 ADD COLUMN payment_id BIGINT REFERENCES payments(id),
 ADD COLUMN command_id UUID REFERENCES accounting_command_receipts(command_id) DEFERRABLE INITIALLY DEFERRED,
 ADD COLUMN origin_kind VARCHAR(24), ADD COLUMN origin_id VARCHAR(80),
 ADD COLUMN component VARCHAR(32), ADD COLUMN accounting_version INTEGER,
 ADD COLUMN evidence_json JSONB,
 ADD COLUMN local_sequence BIGINT,
 ADD COLUMN recorded_at TIMESTAMPTZ,
 ADD CONSTRAINT ledger_v2_shape CHECK (
  (accounting_version IS NULL AND payment_method IS NULL AND payment_id IS NULL AND command_id IS NULL
    AND origin_kind IS NULL AND origin_id IS NULL AND component IS NULL AND evidence_json IS NULL
    AND local_sequence IS NULL AND recorded_at IS NULL) OR
  (accounting_version IS NOT NULL AND accounting_version=2 AND payment_method IS NOT NULL
   AND payment_method IN ('CASH','CARD','TRANSFER','OTHER') AND command_id IS NOT NULL
   AND origin_kind IS NOT NULL AND origin_kind IN ('CASH_SESSION','PAYMENT','EXPENSE','OPERATION')
   AND origin_id IS NOT NULL AND length(btrim(origin_id))>0 AND component IS NOT NULL
   AND evidence_json IS NOT NULL AND jsonb_typeof(evidence_json)='object'
   AND local_sequence IS NOT NULL AND local_sequence>0 AND recorded_at IS NOT NULL AND
   ((event_type='OPENING' AND component='OPENING' AND origin_kind='CASH_SESSION' AND payment_method='CASH'
      AND amount_delta>=0 AND sale_id IS NULL AND expense_id IS NULL AND payment_id IS NULL AND original_event_id IS NULL)
    OR (event_type='PAYMENT' AND component='PAYMENT_CAPTURE' AND origin_kind='PAYMENT' AND amount_delta>0
      AND sale_id IS NOT NULL AND payment_id IS NOT NULL AND expense_id IS NULL AND original_event_id IS NULL)
    OR (event_type='REFUND' AND component='PAYMENT_REFUND' AND origin_kind='PAYMENT' AND amount_delta<0
      AND sale_id IS NOT NULL AND payment_id IS NOT NULL AND expense_id IS NULL AND original_event_id IS NOT NULL)
     OR (event_type='FEE' AND component='FEE_PAID' AND origin_kind='OPERATION' AND amount_delta<0
       AND reason IS NOT NULL AND evidence_ref IS NOT NULL
       AND length(btrim(reason))>=3 AND length(btrim(evidence_ref))>=3)
    OR (event_type='EXPENSE_ACCRUAL' AND component='EXPENSE_ACCRUAL' AND origin_kind='EXPENSE' AND amount_delta>0
      AND expense_id IS NOT NULL AND payment_id IS NULL AND sale_id IS NULL AND original_event_id IS NULL)
    OR (event_type='EXPENSE_PAID' AND component='EXPENSE_SETTLEMENT' AND origin_kind='EXPENSE' AND amount_delta<0
      AND expense_id IS NOT NULL AND payment_id IS NULL AND sale_id IS NULL AND original_event_id IS NULL)
    OR (event_type='ADJUSTMENT' AND component='ADJUSTMENT' AND origin_kind='OPERATION'
      AND original_event_id IS NOT NULL AND reason IS NOT NULL AND evidence_ref IS NOT NULL
       AND length(btrim(reason))>=3 AND length(btrim(evidence_ref))>=3)))
 );
CREATE UNIQUE INDEX ledger_one_opening ON cash_ledger_events(cash_session_id) WHERE event_type='OPENING';
CREATE UNIQUE INDEX ledger_origin_component ON cash_ledger_events(origin_kind,origin_id,component) WHERE accounting_version=2;
CREATE UNIQUE INDEX ledger_cash_sequence ON cash_ledger_events(cash_session_id,local_sequence) WHERE accounting_version=2;
CREATE UNIQUE INDEX ledger_one_refund_per_capture ON cash_ledger_events(original_event_id) WHERE accounting_version=2 AND event_type='REFUND';
CREATE INDEX ledger_cash_time ON cash_ledger_events(cash_session_id,occurred_at,event_type);
CREATE INDEX commercial_recognition_time ON commercial_recognitions(occurred_at,cash_session_id);

CREATE FUNCTION enforce_accounting_ledger_insert() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE runtime_state VARCHAR(24); original cash_ledger_events%ROWTYPE; BEGIN
 SELECT state INTO runtime_state FROM accounting_runtime WHERE singleton;
 IF (NEW.accounting_version IS NULL AND runtime_state<>'PRE_ACTIVATION') OR
    (NEW.accounting_version=2 AND runtime_state<>'ACTIVE') OR runtime_state IS NULL THEN
  RAISE EXCEPTION 'ledger writer incompatible with accounting lifecycle';
 END IF;
 IF NEW.accounting_version=2 THEN
  IF NEW.event_type='OPENING' AND NOT EXISTS(SELECT 1 FROM cash_session_projection WHERE id=NEW.cash_session_id AND opening_cash=NEW.amount_delta) THEN
   RAISE EXCEPTION 'opening posting must match its cash session';
  END IF;
  IF NEW.origin_kind='CASH_SESSION' AND NEW.origin_id<>NEW.cash_session_id::text OR
     NEW.origin_kind='PAYMENT' AND NEW.origin_id<>NEW.payment_id::text OR
     NEW.origin_kind='EXPENSE' AND NEW.origin_id<>NEW.expense_id::text OR
     NEW.origin_kind='OPERATION' AND NEW.origin_id<>NEW.command_id::text THEN
   RAISE EXCEPTION 'posting origin identity mismatch';
  END IF;
  IF NEW.payment_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM payments p JOIN sale_state_projection s ON s.id=p.sale_id JOIN sale_intents i ON i.id=s.sale_intent_id
    WHERE p.id=NEW.payment_id AND p.sale_id=NEW.sale_id AND i.cash_session_id=NEW.cash_session_id AND p.payment_method=NEW.payment_method
     AND p.accounting_command_id=NEW.command_id
    AND p.accounting_version=2 AND p.amount=abs(NEW.amount_delta)
    AND ((NEW.event_type='PAYMENT' AND p.status='CAPTURED' AND p.original_payment_id IS NULL)
      OR (NEW.event_type='REFUND' AND p.status='REFUNDED' AND p.original_payment_id IS NOT NULL))) THEN
   RAISE EXCEPTION 'posting payment source mismatch';
  END IF;
  IF NEW.expense_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM expenses WHERE id=NEW.expense_id AND cash_session_id=NEW.cash_session_id AND amount=abs(NEW.amount_delta)) THEN
   RAISE EXCEPTION 'posting expense source mismatch';
  END IF;
  IF NEW.event_type='REFUND' THEN
   SELECT * INTO original FROM cash_ledger_events WHERE id=NEW.original_event_id;
   IF original.accounting_version IS DISTINCT FROM 2 OR original.event_type IS DISTINCT FROM 'PAYMENT'
    OR original.payment_id IS DISTINCT FROM (SELECT original_payment_id FROM payments WHERE id=NEW.payment_id)
    OR (original.cash_session_id,original.sale_id,original.payment_method,original.amount_delta)
     IS DISTINCT FROM (NEW.cash_session_id,NEW.sale_id,NEW.payment_method,-NEW.amount_delta) THEN
    RAISE EXCEPTION 'refund must compensate one complete capture';
   END IF;
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER accounting_ledger_insert BEFORE INSERT ON cash_ledger_events FOR EACH ROW EXECUTE FUNCTION enforce_accounting_ledger_insert();

CREATE FUNCTION enforce_paid_expense_settlement() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.accounting_version=2 AND NEW.event_type='EXPENSE_PAID' AND NOT EXISTS(
  SELECT 1 FROM expense_settlements s WHERE s.expense_id=NEW.expense_id
   AND s.cash_session_id=NEW.cash_session_id AND s.command_id=NEW.command_id
   AND s.payment_method=NEW.payment_method AND s.amount=abs(NEW.amount_delta)
 ) THEN
  RAISE EXCEPTION 'paid expense posting requires its durable settlement';
 END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER paid_expense_settlement AFTER INSERT ON cash_ledger_events
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION enforce_paid_expense_settlement();

CREATE FUNCTION enforce_complete_cash_coverage() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.coverage='COMPLETE_FROM_OPENING' AND
   ((SELECT state FROM accounting_runtime WHERE singleton) IS DISTINCT FROM 'ACTIVE' OR NOT EXISTS
    (SELECT 1 FROM cash_ledger_events l JOIN cash_session_projection s ON s.id=l.cash_session_id
     CROSS JOIN accounting_runtime r WHERE l.cash_session_id=NEW.cash_session_id AND l.event_type='OPENING'
     AND l.accounting_version=2 AND l.command_id=NEW.command_id AND s.opened_at>=r.accounting_activation_at)) THEN
   RAISE EXCEPTION 'complete coverage requires its accounting opening';
 END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER complete_cash_coverage AFTER INSERT ON cash_accounting_coverage
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION enforce_complete_cash_coverage();

CREATE FUNCTION enforce_accounting_receipt_source() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE fact JSONB; receipt accounting_command_receipts%ROWTYPE; BEGIN
 fact := to_jsonb(NEW);
 IF fact->>'command_id' IS NULL THEN RETURN NEW; END IF;
 SELECT * INTO receipt FROM accounting_command_receipts WHERE command_id=(fact->>'command_id')::uuid;
 IF receipt.command_id IS NULL OR receipt.cash_session_id IS DISTINCT FROM (fact->>'cash_session_id')::bigint
  OR (fact->>'actor_id' IS NOT NULL AND receipt.actor_id IS DISTINCT FROM (fact->>'actor_id')::bigint)
  OR (fact->>'sale_id' IS NOT NULL AND receipt.sale_id IS DISTINCT FROM (fact->>'sale_id')::bigint) THEN
  RAISE EXCEPTION 'accounting fact does not match its command authority';
 END IF;
 IF (TG_TABLE_NAME='cash_accounting_coverage' AND receipt.command_kind<>'CASH_SESSION_OPEN')
  OR (TG_TABLE_NAME='cash_reconciliations' AND receipt.command_kind<>'CASH_SESSION_CLOSE')
  OR (TG_TABLE_NAME='expense_settlements' AND receipt.command_kind<>'EXPENSE_RECORD')
  OR (TG_TABLE_NAME='commercial_recognitions' AND receipt.command_kind<>'COMMERCIAL_RECOGNITION')
  OR (TG_TABLE_NAME='cash_ledger_events' AND NOT (
   (fact->>'event_type'='OPENING' AND receipt.command_kind='CASH_SESSION_OPEN') OR
   (fact->>'event_type'='PAYMENT' AND receipt.command_kind='PAYMENT_CAPTURE') OR
   (fact->>'event_type'='REFUND' AND receipt.command_kind='PAYMENT_REVERSE') OR
   (fact->>'event_type' IN ('EXPENSE_ACCRUAL','EXPENSE_PAID') AND receipt.command_kind='EXPENSE_RECORD') OR
   (fact->>'event_type'='FEE' AND receipt.command_kind='FEE_RECORD') OR
   (fact->>'event_type'='ADJUSTMENT' AND receipt.command_kind='ADJUSTMENT_RECORD'))) THEN
  RAISE EXCEPTION 'accounting fact does not match its command kind';
 END IF;
 RETURN NEW;
END $$;
DO $$ DECLARE table_name TEXT; BEGIN
 FOREACH table_name IN ARRAY ARRAY['cash_ledger_events','cash_accounting_coverage','expense_settlements','cash_reconciliations','commercial_recognitions'] LOOP
  EXECUTE format('CREATE CONSTRAINT TRIGGER accounting_receipt_source AFTER INSERT ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION enforce_accounting_receipt_source()',table_name);
 END LOOP;
END $$;

CREATE FUNCTION require_active_accounting() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF (SELECT state FROM accounting_runtime WHERE singleton) IS DISTINCT FROM 'ACTIVE' THEN
  RAISE EXCEPTION 'accounting facts require active lifecycle';
 END IF;
 RETURN NEW;
END $$;

-- Historical configuration is versioned by insertion, never rewritten.
CREATE TRIGGER immutable_report_formula_config BEFORE UPDATE OR DELETE ON report_formula_config FOR EACH ROW EXECUTE FUNCTION reject_historical_mutation();
DO $$ DECLARE table_name TEXT; BEGIN
 FOREACH table_name IN ARRAY ARRAY['accounting_command_receipts','cash_accounting_coverage','expense_settlements','cash_reconciliations','commercial_recognitions','accounting_zone_versions'] LOOP
  EXECUTE format('CREATE TRIGGER immutable_accounting_history BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_historical_mutation()',table_name);
  IF table_name<>'accounting_zone_versions' THEN
   EXECUTE format('CREATE TRIGGER active_accounting_insert BEFORE INSERT ON %I FOR EACH ROW EXECUTE FUNCTION require_active_accounting()',table_name);
  END IF;
  EXECUTE format('REVOKE ALL ON %I FROM PUBLIC, blackstore_app, blackstore_projection_worker, blackstore_outbox_worker',table_name);
  EXECUTE format('GRANT SELECT, INSERT ON %I TO blackstore_app',table_name);
  EXECUTE format('GRANT SELECT ON %I TO blackstore_auditor',table_name);
 END LOOP;
END $$;
-- Runtime cannot activate itself or change the installation's historical zone.
REVOKE INSERT ON accounting_zone_versions FROM blackstore_app;
REVOKE ALL ON accounting_runtime FROM PUBLIC,blackstore_app,blackstore_projection_worker,blackstore_outbox_worker;
GRANT SELECT ON accounting_runtime TO blackstore_app,blackstore_projection_worker,blackstore_outbox_worker,blackstore_auditor;
GRANT SELECT,UPDATE ON accounting_runtime TO blackstore_migration_owner;
GRANT SELECT,INSERT ON accounting_zone_versions TO blackstore_migration_owner;
GRANT SELECT,INSERT ON cash_accounting_coverage TO blackstore_migration_owner;
GRANT SELECT ON accounting_command_receipts,cash_ledger_events TO blackstore_migration_owner;
GRANT USAGE,SELECT ON SEQUENCE expense_settlements_id_seq TO blackstore_app;
REVOKE UPDATE,DELETE ON report_formula_config FROM blackstore_app,blackstore_projection_worker,blackstore_outbox_worker;
