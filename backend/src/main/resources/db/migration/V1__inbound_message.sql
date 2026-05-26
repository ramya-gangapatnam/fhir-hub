-- V1: inbound_message
--
-- One row per HL7 v2 message POSTed to /ingest/hl7v2. The `id` column is the
-- "hub-assigned message identifier" returned in the 202 ingestion response and
-- referenced by every downstream artifact (idempotency_key, validation_error,
-- audit events). See specs/001-adt-a01-ingestion-inspection/data-model.md §2.1.
--
-- The `updated_at_utc` column is maintained by a BEFORE UPDATE trigger
-- installed in V5 (T010).

CREATE TABLE inbound_message (
    id                         uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
    raw_hl7                    text           NOT NULL,
    msh10_control_id           varchar(199)   NOT NULL,
    msh3_sending_application   varchar(180)   NOT NULL,
    received_at_utc            timestamptz    NOT NULL DEFAULT now(),
    status                     text           NOT NULL,
    correlation_id             uuid           NOT NULL,
    last_error_code            varchar(80),
    last_error_location        varchar(160),
    created_at_utc             timestamptz    NOT NULL DEFAULT now(),
    updated_at_utc             timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT chk_inbound_message_status
        CHECK (status IN ('RECEIVED', 'VALIDATING', 'TRANSFORMED', 'PERSISTED', 'FAILED'))
);

-- Inspector list default ordering (FR-012).
CREATE INDEX idx_inbound_message_received_at_desc
    ON inbound_message (received_at_utc DESC);

-- Inspector list status filter (FR-012).
CREATE INDEX idx_inbound_message_status
    ON inbound_message (status);

-- Inspector MSH-10 exact-match search (FR-012).
CREATE INDEX idx_inbound_message_msh10
    ON inbound_message (msh10_control_id);

-- Diagnostic lookups from log lines (Principle VII).
CREATE INDEX idx_inbound_message_correlation_id
    ON inbound_message (correlation_id);
