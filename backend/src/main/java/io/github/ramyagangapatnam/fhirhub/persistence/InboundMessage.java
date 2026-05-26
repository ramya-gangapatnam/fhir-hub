package io.github.ramyagangapatnam.fhirhub.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row per HL7 v2 ADT^A01 message POSTed to {@code /ingest/hl7v2}, valid or invalid. Backed by
 * the {@code inbound_message} table created in Flyway V1.
 *
 * <p>The {@code raw_hl7} body is the source of truth for replay (Inspector US2). All consumers that
 * move past the request thread carry only this entity's {@link #id}, never the body — preserves the
 * seam-invariant in {@code plan.md §5}.
 */
@Entity
@Table(name = "inbound_message")
public class InboundMessage {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "raw_hl7", nullable = false, columnDefinition = "text")
  private String rawHl7;

  @Column(name = "msh10_control_id", nullable = false, length = 199)
  private String msh10ControlId;

  @Column(name = "msh3_sending_application", nullable = false, length = 180)
  private String msh3SendingApplication;

  @Column(name = "received_at_utc", nullable = false)
  private OffsetDateTime receivedAtUtc;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, columnDefinition = "text")
  private InboundMessageStatus status;

  @Column(name = "correlation_id", nullable = false)
  private UUID correlationId;

  @Column(name = "last_error_code", length = 80)
  private String lastErrorCode;

  @Column(name = "last_error_location", length = 160)
  private String lastErrorLocation;

  @Column(name = "created_at_utc", nullable = false, updatable = false)
  private OffsetDateTime createdAtUtc;

  @Column(name = "updated_at_utc", nullable = false)
  private OffsetDateTime updatedAtUtc;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getRawHl7() {
    return rawHl7;
  }

  public void setRawHl7(String rawHl7) {
    this.rawHl7 = rawHl7;
  }

  public String getMsh10ControlId() {
    return msh10ControlId;
  }

  public void setMsh10ControlId(String msh10ControlId) {
    this.msh10ControlId = msh10ControlId;
  }

  public String getMsh3SendingApplication() {
    return msh3SendingApplication;
  }

  public void setMsh3SendingApplication(String msh3SendingApplication) {
    this.msh3SendingApplication = msh3SendingApplication;
  }

  public OffsetDateTime getReceivedAtUtc() {
    return receivedAtUtc;
  }

  public void setReceivedAtUtc(OffsetDateTime receivedAtUtc) {
    this.receivedAtUtc = receivedAtUtc;
  }

  public InboundMessageStatus getStatus() {
    return status;
  }

  public void setStatus(InboundMessageStatus status) {
    this.status = status;
  }

  public UUID getCorrelationId() {
    return correlationId;
  }

  public void setCorrelationId(UUID correlationId) {
    this.correlationId = correlationId;
  }

  public String getLastErrorCode() {
    return lastErrorCode;
  }

  public void setLastErrorCode(String lastErrorCode) {
    this.lastErrorCode = lastErrorCode;
  }

  public String getLastErrorLocation() {
    return lastErrorLocation;
  }

  public void setLastErrorLocation(String lastErrorLocation) {
    this.lastErrorLocation = lastErrorLocation;
  }

  public OffsetDateTime getCreatedAtUtc() {
    return createdAtUtc;
  }

  public void setCreatedAtUtc(OffsetDateTime createdAtUtc) {
    this.createdAtUtc = createdAtUtc;
  }

  public OffsetDateTime getUpdatedAtUtc() {
    return updatedAtUtc;
  }

  public void setUpdatedAtUtc(OffsetDateTime updatedAtUtc) {
    this.updatedAtUtc = updatedAtUtc;
  }
}
