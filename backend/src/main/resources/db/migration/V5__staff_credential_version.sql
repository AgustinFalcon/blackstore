-- Forward-only. This lock also covers provisioning's subsequent session revocations until commit.
ALTER TABLE staff_users ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 0 CHECK(credential_version >= 0);
CREATE FUNCTION advance_staff_credential_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.password_hash IS DISTINCT FROM OLD.password_hash THEN
    PERFORM pg_advisory_xact_lock(hashtextextended('staff-credential:' || OLD.id::text,0));
    NEW.credential_version := OLD.credential_version + 1;
  END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER staff_credential_version BEFORE UPDATE OF password_hash ON staff_users
FOR EACH ROW EXECUTE FUNCTION advance_staff_credential_version();
