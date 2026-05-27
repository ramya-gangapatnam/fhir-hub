package io.github.ramyagangapatnam.fhirhub.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA store for {@link FhirResource}. The higher-level upsert/read-by-id orchestration
 * — HAPI {@code IParser} serialization, {@code version_id} bump, weak ETag emission — lives in
 * {@code io.github.ramyagangapatnam.fhirhub.fhir.FhirResourceRepository} (T033). Keep this
 * interface as a thin persistence boundary so the business logic can be unit-tested without Spring
 * Data.
 */
@Repository
public interface FhirResourceJpaRepository extends JpaRepository<FhirResource, String> {

  Optional<FhirResource> findByResourceTypeAndId(FhirResourceType resourceType, String id);

  Optional<FhirResource> findByResourceTypeAndBusinessIdentifierSystemAndBusinessIdentifierValue(
      FhirResourceType resourceType,
      String businessIdentifierSystem,
      String businessIdentifierValue);
}
