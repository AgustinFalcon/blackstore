-- Explicit provisioning only. No defaults, random IDs, seeds or historical backfill.
CREATE TABLE pos_terminal_context (
 terminal_id BIGINT PRIMARY KEY REFERENCES terminals(id) ON DELETE RESTRICT,
 client_instance_id UUID NOT NULL,
 device_id VARCHAR(80) NOT NULL CHECK (char_length(btrim(device_id))>0 AND device_id=btrim(device_id)),
 provisioned_by_ref VARCHAR(200) NOT NULL CHECK (char_length(btrim(provisioned_by_ref))>0),
 installation_evidence_ref VARCHAR(200) NOT NULL CHECK (char_length(btrim(installation_evidence_ref))>0),
 provisioned_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(client_instance_id,device_id)
);
CREATE TRIGGER immutable_pos_context BEFORE UPDATE OR DELETE ON pos_terminal_context
 FOR EACH ROW EXECUTE FUNCTION reject_historical_mutation();
CREATE TRIGGER immutable_pos_context_truncate BEFORE TRUNCATE ON pos_terminal_context
 FOR EACH STATEMENT EXECUTE FUNCTION reject_historical_mutation();
REVOKE ALL ON pos_terminal_context FROM PUBLIC,blackstore_app,blackstore_projection_worker,blackstore_outbox_worker,blackstore_auditor;
GRANT SELECT ON pos_terminal_context TO blackstore_app;
GRANT SELECT ON terminals TO blackstore_app;

-- SELECT FOR SHARE requires UPDATE rights in PostgreSQL. This narrow definer function
-- grants only the lock capability, never runtime terminal UPDATE privileges.
CREATE FUNCTION lock_pos_terminal(requested_terminal BIGINT) RETURNS BOOLEAN
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE enabled BOOLEAN;
BEGIN
 SELECT active INTO enabled FROM public.terminals WHERE id=requested_terminal FOR SHARE;
 RETURN COALESCE(enabled,FALSE);
END; $$;
REVOKE ALL ON FUNCTION lock_pos_terminal(BIGINT) FROM PUBLIC,blackstore_projection_worker,blackstore_outbox_worker,blackstore_auditor;
GRANT EXECUTE ON FUNCTION lock_pos_terminal(BIGINT) TO blackstore_app;
