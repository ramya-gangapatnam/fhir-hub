package io.github.ramyagangapatnam.fhirhub.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row per persisted FHIR R4 resource. Backed by the single-table JSONB layout from Flyway V3
 * (see data-model.md §2.4 + research.md §2).
 *
 * <p>{@link #contentJson} stores the HAPI FHIR {@code IParser}-serialised resource as JSONB —
 * Hibernate 6 binds it via {@link SqlTypes#JSON} so the JDBC driver round-trips the {@code jsonb}
 * column without us writing a {@code UserType}.
 *
 * <p>Principle VIII idempotency is enforced TWICE: once by {@link IdempotencyKey} on {@code
 * (sending_application, msh10_control_id)}, and once at this layer by the partial unique index on
 * {@code (resource_type, business_identifier_system, business_identifier_value)} which catches the
 * Patient-identifier-collision edge case in spec §Edge Cases.
 */
@Entity
@Table(name = "fhir_resource")
public class FhirResource {

  @Id
  @Column(name = "id", nullable = false, updatable = false, columnDefinition = "text")
  private String id;

  @Enumerated(EnumType.STRING)
  @Column(name = "resource_type", nullable = false, columnDefinition = "text")
  private FhirResourceType resourceType;

  @Column(name = "version_id", nullable = false)
  private long versionId;

  @Column(name = "last_updated_utc", nullable = false)
  private OffsetDateTime lastUpdatedUtc;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "content_json", nullable = false, columnDefinition = "jsonb")
  private String contentJson;

  @Column(name = "business_identifier_system", length = 255)
  private String businessIdentifierSystem;

  @Column(name = "business_identifier_value", length = 255)
  private String businessIdentifierValue;

  @Column(name = "created_at_utc", nullable = false, updatable = false)
  private OffsetDateTime createdAtUtc;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public FhirResourceType getResourceType() {
    return resourceType;
  }

  public void setResourceType(FhirResourceType resourceType) {
    this.resourceType = resourceType;
  }

  public long getVersionId() {
    return versionId;
  }

  public void setVersionId(long versionId) {
    this.versionId = versionId;
  }

  public OffsetDateTime getLastUpdatedUtc() {
    return lastUpdatedUtc;
  }

  public void setLastUpdatedUtc(OffsetDateTime lastUpdatedUtc) {
    this.lastUpdatedUtc = lastUpdatedUtc;
  }

  public String getContentJson() {
    return contentJson;
  }

  public void setContentJson(String contentJson) {
    this.contentJson = contentJson;
  }

  public String getBusinessIdentifierSystem() {
    return businessIdentifierSystem;
  }

  public void setBusinessIdentifierSystem(String businessIdentifierSystem) {
    this.businessIdentifierSystem = businessIdentifierSystem;
  }

  public String getBusinessIdentifierValue() {
    return businessIdentifierValue;
  }

  public void setBusinessIdentifierValue(String businessIdentifierValue) {
    this.businessIdentifierValue = businessIdentifierValue;
  }

  public OffsetDateTime getCreatedAtUtc() {
    return createdAtUtc;
  }

  public void setCreatedAtUtc(OffsetDateTime createdAtUtc) {
    this.createdAtUtc = createdAtUtc;
  }
}
