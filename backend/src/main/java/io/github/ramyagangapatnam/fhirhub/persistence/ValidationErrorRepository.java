package io.github.ramyagangapatnam.fhirhub.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Spring Data repository for {@link ValidationErrorRow}. */
@Repository
public interface ValidationErrorRepository extends JpaRepository<ValidationErrorRow, UUID> {

  List<ValidationErrorRow> findByInboundMessageId(UUID inboundMessageId);
}
