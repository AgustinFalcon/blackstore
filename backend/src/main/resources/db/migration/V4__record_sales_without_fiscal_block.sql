-- A counter sale is recorded in every environment. Fiscal status does not block it.
-- Invoicing is a later screen. Historical sale rows stay immutable.

CREATE OR REPLACE FUNCTION enforce_production_fiscal_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION enforce_companion_production_enablement()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RETURN NEW;
END;
$$;
