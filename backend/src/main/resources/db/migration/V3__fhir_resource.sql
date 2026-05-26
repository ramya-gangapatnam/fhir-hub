-- V3: fhir_resource
--
-- Single-table JSONB storage for the two in-scope FHIR resources (Patient,
-- Encounter). Scope is fixed by Principle X; expanding `resource_type`
-- requires an ADR. See data-model.md §2.4 and research.md §2.
--
-- Principle VIII (idempotency at the persistence layer) is enforced TWICE:
--   - In V4, by idempotency_key (sending_application, msh10_control_id).
--   - Here, by the partial unique index on
--     (resource_type, business_identifier_system, business_identifier_value).
-- The partial unique index handles the Patient-identifier-collision edge case
-- where two distinct HL7 messages map to the same Patient identifier.

CREATE TABLE fhir_resource (
    id                            text           PRIMARY KEY,
    resource_type                 text           NOT NULL,
    version_id                    bigint         NOT NULL DEFAULT 1,
    last_updated_utc              timestamptz    NOT NULL DEFAULT now(),
    content_json                  jsonb          NOT NULL,
    business_identifier_system    varchar(255),
    business_identifier_value     varchar(255),
    created_at_utc                timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT chk_fhir_resource_type
        CHECK (resource_type IN ('Patient', 'Encounter'))
);

-- Principle VIII: second-layer idempotency for the Patient
-- identifier-collision edge case. Partial so that rows without a business
-- identifier (e.g., minimal Encounter persistence) don't collide on NULLs.
CREATE UNIQUE INDEX idx_fhir_resource_business_identifier
    ON fhir_resource (resource_type, business_identifier_system, business_identifier_value)
    WHERE business_identifier_value IS NOT NULL;

-- FHIR `read` query path: GET /fhir/{resource_type}/{id}.
CREATE INDEX idx_fhir_resource_type_id
    ON fhir_resource (resource_type, id);
