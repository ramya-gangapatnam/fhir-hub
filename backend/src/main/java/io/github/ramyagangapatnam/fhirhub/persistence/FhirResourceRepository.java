package io.github.ramyagangapatnam.fhirhub.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Spring Data repository for {@link FhirResource}. */
@Repository
public interface FhirResourceRepository extends JpaRepository<FhirResource, String> {

  Optional<FhirResource> findByResourceTypeAndId(FhirResourceType resourceType, String id);

  Optional<FhirResource> findByResourceTypeAndBusinessIdentifierSystemAndBusinessIdentifierValue(
      FhirResourceType resourceType,
      String businessIdentifierSystem,
      String businessIdentifierValue);
}
