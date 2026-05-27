package io.github.ramyagangapatnam.fhirhub.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Primary Principle VIII (Idempotent Ingestion) enforcement point. One row per {@code
 * (sending_application, msh10_control_id)} pair — the DB unique constraint installed by Flyway V4
 * makes the loser of a concurrent INSERT raise {@code UNIQUE_VIOLATION} which the application
 * converts into a non-creating success.
 *
 * <p>{@code inboundMessageId} pins the FIRST inbound message that established this key; replays do
 * NOT rewrite it.
 */
@Entity
@Table(
    name = "idempotency_key",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_idempotency_key_sender_msh10",
            columnNames = {"sending_application", "msh10_control_id"}))
public class IdempotencyKey {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "sending_application", nullable = false, length = 180)
  private String sendingApplication;

  @Column(name = "msh10_control_id", nullable = false, length = 199)
  private String msh10ControlId;

  @Column(name = "inbound_message_id", nullable = false)
  private UUID inboundMessageId;

  @Column(name = "patient_resource_id")
  private String patientResourceId;

  @Column(name = "encounter_resource_id")
  private String encounterResourceId;

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

  public String getSendingApplication() {
    return sendingApplication;
  }

  public void setSendingApplication(String sendingApplication) {
    this.sendingApplication = sendingApplication;
  }

  public String getMsh10ControlId() {
    return msh10ControlId;
  }

  public void setMsh10ControlId(String msh10ControlId) {
    this.msh10ControlId = msh10ControlId;
  }

  public UUID getInboundMessageId() {
    return inboundMessageId;
  }

  public void setInboundMessageId(UUID inboundMessageId) {
    this.inboundMessageId = inboundMessageId;
  }

  public String getPatientResourceId() {
    return patientResourceId;
  }

  public void setPatientResourceId(String patientResourceId) {
    this.patientResourceId = patientResourceId;
  }

  public String getEncounterResourceId() {
    return encounterResourceId;
  }

  public void setEncounterResourceId(String encounterResourceId) {
    this.encounterResourceId = encounterResourceId;
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
