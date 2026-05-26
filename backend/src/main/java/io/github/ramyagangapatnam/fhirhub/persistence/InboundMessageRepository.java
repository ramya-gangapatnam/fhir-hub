package io.github.ramyagangapatnam.fhirhub.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Spring Data repository for {@link InboundMessage}. */
@Repository
public interface InboundMessageRepository extends JpaRepository<InboundMessage, UUID> {}
