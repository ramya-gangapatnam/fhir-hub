package io.github.ramyagangapatnam.fhirhub.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import io.github.ramyagangapatnam.fhirhub.persistence.FhirResource;
import io.github.ramyagangapatnam.fhirhub.persistence.FhirResourceJpaRepository;
import io.github.ramyagangapatnam.fhirhub.persistence.FhirResourceType;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business-facing repository for FHIR R4 resources. Handles the {@code IParser} serialization, the
 * {@code version_id} bump on every upsert, the {@code last_updated_utc} stamp, and the weak {@code
 * ETag} value the FHIR read controller (T038) returns to clients.
 *
 * <p>Principle VIII secondary enforcement: an attempt to insert a second row with the same {@code
 * (resource_type, business_identifier_system, business_identifier_value)} triple hits the partial
 * unique index from Flyway V3. The arbiter (T034) is the primary enforcement boundary; this layer
 * catches the Patient-identifier-collision edge case where two distinct messages map to the same
 * Patient identifier.
 */
@Component
public class FhirResourceRepository {

  private static final FhirContext FHIR_CONTEXT = FhirContext.forR4();

  private final FhirResourceJpaRepository jpa;
  private final IParser parser;

  public FhirResourceRepository(FhirResourceJpaRepository jpa) {
    this.jpa = jpa;
    this.parser = FHIR_CONTEXT.newJsonParser();
  }

  /**
   * Upsert a FHIR resource. Bumps {@code version_id} on every call (insert or update), stamps
   * {@code last_updated_utc}, serializes via HAPI {@link IParser}, and writes the partial-unique
   * business identifier triple from Flyway V3.
   *
   * <p>If two callers race to insert resources whose business identifier collides
   * (Patient-identifier collision per data-model.md §4), the loser surfaces {@link
   * DataIntegrityViolationException}; callers fold that into {@code PERSIST_IDEMPOTENCY_CONFLICT}
   * so the contract of "one resource per business identifier" is preserved.
   */
  @Transactional
  public Stored upsert(FhirResourceType resourceType, IBaseResource resource) {
    Objects.requireNonNull(resourceType, "resourceType");
    Objects.requireNonNull(resource, "resource");

    String logicalId = resource.getIdElement().getIdPart();
    if (logicalId == null || logicalId.isEmpty()) {
      throw new IllegalArgumentException(
          "FHIR resource is missing a logical id; the caller must mint it before upsert.");
    }

    BusinessIdentifier bi = extractBusinessIdentifier(resource);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    String serialized = parser.encodeResourceToString(resource);

    Optional<FhirResource> existing = jpa.findByResourceTypeAndId(resourceType, logicalId);
    FhirResource row = existing.orElseGet(FhirResource::new);
    if (existing.isEmpty()) {
      row.setId(logicalId);
      row.setResourceType(resourceType);
      row.setVersionId(1L);
      row.setCreatedAtUtc(now);
    } else {
      row.setVersionId(existing.get().getVersionId() + 1L);
    }
    row.setContentJson(serialized);
    row.setLastUpdatedUtc(now);
    row.setBusinessIdentifierSystem(bi.system());
    row.setBusinessIdentifierValue(bi.value());

    FhirResource saved = jpa.saveAndFlush(row);
    return Stored.from(saved);
  }

  /**
   * Read-by-id used by the FHIR read controller. Returns {@link Stored} so the controller can
   * stream {@code content_json} straight back and emit {@code ETag} + {@code Last-Modified}.
   */
  @Transactional(readOnly = true)
  public Optional<Stored> findById(FhirResourceType resourceType, String id) {
    return jpa.findByResourceTypeAndId(resourceType, id).map(Stored::from);
  }

  /**
   * Lookup by business identifier — used by the transformation pipeline before minting a new id.
   */
  @Transactional(readOnly = true)
  public Optional<Stored> findByBusinessIdentifier(
      FhirResourceType resourceType, String system, String value) {
    if (system == null || value == null || value.isEmpty()) {
      return Optional.empty();
    }
    return jpa.findByResourceTypeAndBusinessIdentifierSystemAndBusinessIdentifierValue(
            resourceType, system, value)
        .map(Stored::from);
  }

  private static BusinessIdentifier extractBusinessIdentifier(IBaseResource resource) {
    if (resource instanceof Patient p && !p.getIdentifier().isEmpty()) {
      Identifier first = p.getIdentifier().get(0);
      return new BusinessIdentifier(nullIfEmpty(first.getSystem()), nullIfEmpty(first.getValue()));
    }
    if (resource instanceof Encounter e && !e.getIdentifier().isEmpty()) {
      Identifier first = e.getIdentifier().get(0);
      return new BusinessIdentifier(nullIfEmpty(first.getSystem()), nullIfEmpty(first.getValue()));
    }
    return new BusinessIdentifier(null, null);
  }

  private static String nullIfEmpty(String s) {
    return (s == null || s.isEmpty()) ? null : s;
  }

  /** Persisted view, plus the weak ETag the FHIR read endpoint emits. */
  public record Stored(
      String id,
      FhirResourceType resourceType,
      long versionId,
      OffsetDateTime lastUpdatedUtc,
      String contentJson) {

    public String etag() {
      return "W/\"" + versionId + "\"";
    }

    static Stored from(FhirResource row) {
      return new Stored(
          row.getId(),
          row.getResourceType(),
          row.getVersionId(),
          row.getLastUpdatedUtc(),
          row.getContentJson());
    }
  }

  private record BusinessIdentifier(String system, String value) {}
}
