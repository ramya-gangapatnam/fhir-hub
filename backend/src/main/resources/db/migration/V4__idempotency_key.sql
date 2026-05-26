-- V4: idempotency_key
--
-- Primary Principle VIII enforcement point: one row per (sending_application,
-- msh10_control_id) pair. Concurrent POSTs of the same message produce one
-- row; the loser raises UNIQUE_VIOLATION which the application
-- (IdempotencyArbiter, T034) converts into a non-creating success.
--
-- `inbound_message_id` points to the FIRST inbound_message that established
-- the key. Replays do NOT update this column (see data-model.md §2.3 and
-- the lifecycle diagram in §3).
--
-- `patient_resource_id` / `encounter_resource_id` are populated once the
-- corresponding fhir_resource row exists (FK targets fhir_resource.id).
-- Both are nullable so the row can be inserted before transformation
-- completes, with the FKs deferred-style by sequencing in the application.

CREATE TABLE idempotency_key (
    id                       uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
    sending_application      varchar(180)   NOT NULL,
    msh10_control_id         varchar(199)   NOT NULL,
    inbound_message_id       uuid           NOT NULL,
    patient_resource_id      text,
    encounter_resource_id    text,
    created_at_utc           timestamptz    NOT NULL DEFAULT now(),
    updated_at_utc           timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT uq_idempotency_key_sender_msh10
        UNIQUE (sending_application, msh10_control_id),
    CONSTRAINT fk_idempotency_key_inbound_message
        FOREIGN KEY (inbound_message_id)
        REFERENCES inbound_message (id),
    CONSTRAINT fk_idempotency_key_patient_resource
        FOREIGN KEY (patient_resource_id)
        REFERENCES fhir_resource (id),
    CONSTRAINT fk_idempotency_key_encounter_resource
        FOREIGN KEY (encounter_resource_id)
        REFERENCES fhir_resource (id)
);
