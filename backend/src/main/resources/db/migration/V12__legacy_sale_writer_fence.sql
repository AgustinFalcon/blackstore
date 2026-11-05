-- Rollback safety is enforced by PostgreSQL, not by trusting the deployed binary.
-- A pre-V8 binary may still try to append a legacy outbox command after accounting
-- activation.  The deferred check lets the V2 admission transaction create its
-- receipt, while rejecting an orphan legacy command at commit.
CREATE FUNCTION public.enforce_sale_outbox_lifecycle() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE runtime_state VARCHAR(24);
BEGIN
  -- This fence relies on READ COMMITTED taking a fresh statement snapshot after
  -- the lifecycle lock. Snapshot-stable writers could otherwise retain a
  -- PRE_ACTIVATION observation across a concurrently committed activation.
  IF current_setting('transaction_isolation') <> 'read committed' THEN
    RAISE EXCEPTION 'sale writer requires read committed lifecycle fencing';
  END IF;

  PERFORM pg_advisory_xact_lock_shared(721455258801);
  SELECT state INTO runtime_state FROM public.accounting_runtime WHERE singleton;

  IF runtime_state = 'PRE_ACTIVATION' THEN
    RETURN NULL;
  END IF;

  IF runtime_state = 'ACTIVE' AND EXISTS (
    SELECT 1 FROM public.sale_command_admission_receipts receipt
    WHERE receipt.outbox_id = NEW.id
  ) THEN
    RETURN NULL;
  END IF;

  RAISE EXCEPTION 'sale writer incompatible with accounting lifecycle';
END; $$;

CREATE CONSTRAINT TRIGGER sale_outbox_lifecycle_fence
AFTER INSERT ON public.storecore_outbox_commands
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION public.enforce_sale_outbox_lifecycle();
