package io.github.ramyagangapatnam.fhirhub.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Structured validation/transformation finding attached to an {@link InboundMessage}. Mirrors the
 * {@code validation_error} table from Flyway V2.
 *
 * <p>Named with the {@code Row} suffix to disambiguate from the in-memory {@code
 * Adt01SchemaValidator.Issue} record without dropping {@code ValidationError} from the public
 * surface entirely (the column and table names stay aligned with data-model.md §2.2).
 *
 * <p>{@code summaryShort} (column {@code summary_safe}) MUST be a display-safe summary, never a
 * field value from the source message — Principle I.
 */
@Entity
@Table(name = "validation_error")
public class ValidationErrorRow {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "inbound_message_id", nullable = false)
  private UUID inboundMessageId;

  @Column(name = "error_code", nullable = false, length = 80)
  private String errorCode;

  @Column(name = "segment_field", length = 160)
  private String segmentField;

  @Column(name = "summary_safe", nullable = false, length = 500)
  private String summarySafe;

  @Column(name = "created_at_utc", nullable = false, updatable = false)
  private OffsetDateTime createdAtUtc;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public UUID getInboundMessageId() {
    return inboundMessageId;
  }

  public void setInboundMessageId(UUID inboundMessageId) {
    this.inboundMessageId = inboundMessageId;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public void setErrorCode(String errorCode) {
    this.errorCode = errorCode;
  }

  public String getSegmentField() {
    return segmentField;
  }

  public void setSegmentField(String segmentField) {
    this.segmentField = segmentField;
  }

  public String getSummarySafe() {
    return summarySafe;
  }

  public void setSummarySafe(String summarySafe) {
    this.summarySafe = summarySafe;
  }

  public OffsetDateTime getCreatedAtUtc() {
    return createdAtUtc;
  }

  public void setCreatedAtUtc(OffsetDateTime createdAtUtc) {
    this.createdAtUtc = createdAtUtc;
  }
}
