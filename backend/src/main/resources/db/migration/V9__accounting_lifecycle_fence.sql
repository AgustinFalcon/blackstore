-- A transaction admitted under one lifecycle state must finish before that state changes.
-- Shared writer locks require no table privilege expansion; the migration owner takes
-- the exclusive side before V8's irreversible lifecycle validation runs.
CREATE FUNCTION fence_accounting_lifecycle_transition() RETURNS trigger
LANGUAGE plpgsql AS $$ BEGIN
  PERFORM pg_advisory_xact_lock(721455258801);
  RETURN NEW;
END $$;

CREATE TRIGGER accounting_lifecycle_fence
BEFORE UPDATE ON accounting_runtime
FOR EACH ROW EXECUTE FUNCTION fence_accounting_lifecycle_transition();
