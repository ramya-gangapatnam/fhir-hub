package io.github.ramyagangapatnam.fhirhub.persistence;

/**
 * Lifecycle states for an {@link InboundMessage}, mirroring the CHECK constraint installed by
 * Flyway V1 ({@code chk_inbound_message_status}) and the lifecycle diagram in {@code data-model.md
 * §3}. The enum names are the wire/DB values verbatim — do NOT lower-case or abbreviate, the DB
 * will reject mismatches.
 */
public enum InboundMessageStatus {
  RECEIVED,
  VALIDATING,
  TRANSFORMED,
  PERSISTED,
  FAILED
}
