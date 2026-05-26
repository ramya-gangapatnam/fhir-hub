-- V5: shared updated_at trigger function + BEFORE UPDATE triggers.
--
-- The function mirrors the `updated_at_utc` columns introduced in V1
-- (inbound_message) and V4 (idempotency_key). validation_error and
-- fhir_resource intentionally do NOT have updated_at_utc — validation_error
-- rows are immutable once written, and fhir_resource versioning is driven by
-- explicit application logic (version_id bumps + last_updated_utc set in the
-- repository upsert, T033).

CREATE OR REPLACE FUNCTION set_updated_at_utc()
RETURNS trigger AS $$
BEGIN
    NEW.updated_at_utc := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_inbound_message_set_updated_at_utc
    BEFORE UPDATE ON inbound_message
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at_utc();

CREATE TRIGGER trg_idempotency_key_set_updated_at_utc
    BEFORE UPDATE ON idempotency_key
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at_utc();
