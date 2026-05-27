package io.github.ramyagangapatnam.fhirhub.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Spring Data repository for {@link IdempotencyKey}. */
@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

  Optional<IdempotencyKey> findBySendingApplicationAndMsh10ControlId(
      String sendingApplication, String msh10ControlId);
}
