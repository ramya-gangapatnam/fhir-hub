-- V2: validation_error
--
-- Structured validation/transformation failures attached to an inbound_message.
-- `summary_safe` is the display-safe summary; it MUST NOT echo source-message
-- values (Principle I — PHI Confidentiality in Logs). See data-model.md §2.2.

CREATE TABLE validation_error (
    id                   uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
    inbound_message_id   uuid           NOT NULL,
    error_code           varchar(80)    NOT NULL,
    segment_field        varchar(160),
    summary_safe         varchar(500)   NOT NULL,
    created_at_utc       timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT fk_validation_error_inbound_message
        FOREIGN KEY (inbound_message_id)
        REFERENCES inbound_message (id)
        ON DELETE CASCADE
);

CREATE INDEX idx_validation_error_inbound_message_id
    ON validation_error (inbound_message_id);
