-- Only receipts admitted by V10-V12 have proven historical V1 provenance.
-- Do not rewrite their payload hashes or manufacture receipts for older intents.
ALTER TABLE public.sale_lines ADD COLUMN variant_id VARCHAR(80) NULL;
ALTER TABLE public.sale_lines ADD COLUMN price_version VARCHAR(80) NULL;
ALTER TABLE public.sale_lines ADD CONSTRAINT sale_line_catalog_identity_complete
 CHECK ((variant_id IS NULL AND price_version IS NULL) OR (variant_id IS NOT NULL AND price_version IS NOT NULL));

CREATE TABLE public.sale_command_admission_contracts (
 command_id UUID PRIMARY KEY REFERENCES public.sale_command_admission_receipts(command_id) ON DELETE RESTRICT,
 contract_family VARCHAR(16) NOT NULL,
 fingerprint_version VARCHAR(32) NOT NULL,
 canonical_route VARCHAR(96) NOT NULL,
 CONSTRAINT sale_admission_contract_tuple CHECK (
  (contract_family='sale-v2' AND fingerprint_version='sale-admission-v1' AND canonical_route IN (
   '/api/v2/sales/reservations',
   '/api/v2/sales/{operationId}/commit',
   '/api/v2/sales/{operationId}/release')) OR
  (contract_family='sale-v3' AND fingerprint_version='sale-admission-v2' AND canonical_route='/api/v3/sales/reservations')
 )
);

CREATE FUNCTION public.validate_sale_admission_contract() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,public,pg_temp AS $$
BEGIN
 IF NOT EXISTS (
  SELECT 1 FROM public.sale_command_admission_receipts r WHERE r.command_id=NEW.command_id AND (
   (r.kind='Reserve' AND NEW.canonical_route IN ('/api/v2/sales/reservations','/api/v3/sales/reservations')) OR
   (r.kind='Commit' AND NEW.canonical_route='/api/v2/sales/{operationId}/commit') OR
   (r.kind='Release' AND NEW.canonical_route='/api/v2/sales/{operationId}/release')
  )
 ) THEN RAISE EXCEPTION 'sale admission contract mismatch'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER sale_admission_contract_consistency BEFORE INSERT ON public.sale_command_admission_contracts
 FOR EACH ROW EXECUTE FUNCTION public.validate_sale_admission_contract();

INSERT INTO public.sale_command_admission_contracts(command_id,contract_family,fingerprint_version,canonical_route)
 SELECT command_id,'sale-v2','sale-admission-v1',CASE kind
  WHEN 'Reserve' THEN '/api/v2/sales/reservations'
  WHEN 'Commit' THEN '/api/v2/sales/{operationId}/commit'
  WHEN 'Release' THEN '/api/v2/sales/{operationId}/release'
 END FROM public.sale_command_admission_receipts;

-- A foreign key in the other direction alone would still allow a new receipt
-- without metadata. Check coverage at physical commit, after both inserts.
CREATE FUNCTION public.require_sale_admission_contract() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,public,pg_temp AS $$
BEGIN
 IF NOT EXISTS (SELECT 1 FROM public.sale_command_admission_contracts m WHERE m.command_id=NEW.command_id) THEN
  RAISE EXCEPTION 'sale admission contract required';
 END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER sale_admission_contract_required
 AFTER INSERT ON public.sale_command_admission_receipts DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION public.require_sale_admission_contract();

CREATE TRIGGER immutable_sale_admission_contract BEFORE UPDATE OR DELETE ON public.sale_command_admission_contracts
 FOR EACH ROW EXECUTE FUNCTION public.reject_historical_mutation();
CREATE TRIGGER immutable_sale_admission_contract_truncate BEFORE TRUNCATE ON public.sale_command_admission_contracts
 FOR EACH STATEMENT EXECUTE FUNCTION public.reject_historical_mutation();
REVOKE ALL ON public.sale_command_admission_contracts FROM PUBLIC,blackstore_app,blackstore_projection_worker,blackstore_outbox_worker,blackstore_auditor;
GRANT SELECT,INSERT ON public.sale_command_admission_contracts TO blackstore_app;
GRANT SELECT ON public.sale_command_admission_contracts TO blackstore_auditor;
REVOKE ALL ON FUNCTION public.validate_sale_admission_contract(),public.require_sale_admission_contract() FROM PUBLIC;
