package io.github.ramyagangapatnam.fhirhub.inspector;

import io.github.ramyagangapatnam.fhirhub.persistence.InboundMessage;
import io.github.ramyagangapatnam.fhirhub.persistence.InboundMessageRepository;
import io.github.ramyagangapatnam.fhirhub.persistence.InboundMessageStatus;
import io.github.ramyagangapatnam.fhirhub.testsupport.AbstractInspectorWebTest;
import io.github.ramyagangapatnam.fhirhub.testsupport.Hl7Fixtures;
import io.github.ramyagangapatnam.fhirhub.testsupport.InboundMessageSeed;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * T044 — Contract test for {@code POST /inspector/messages/{messageId}/replay}.
 *
 * <p>Driven from {@code contracts/inspector.openapi.yaml}. Asserts:
 *
 * <ul>
 *   <li>200 on a successful replay with {@code previousStatus}, {@code newStatus}, {@code
 *       replayedAtUtc}, {@code correlationId};
 *   <li>422 + {@code REPLAY_REVALIDATION_FAILED} when the persisted body is still invalid;
 *   <li>404 on an unknown {@code messageId}.
 * </ul>
 *
 * <p>The ingest endpoint rejects invalid HL7 synchronously (400) and never persists it, so the
 * FAILED row for the 422 leg is seeded directly through the repository.
 *
 * <p>Per Principle IV this was written and observed to fail (404, no handler) before T048.
 */
class InspectorReplayContractTest extends AbstractInspectorWebTest {

  @Autowired private InboundMessageRepository inboundMessages;

  @Test
  void replayOfValidMessageReturns200WithStatusTransition() {
    InboundMessage seeded =
        inboundMessages.save(
            InboundMessageSeed.row(
                Hl7Fixtures.GOOD,
                "ADMIT_SYSTEM",
                "REPLAY_OK_1",
                InboundMessageStatus.RECEIVED,
                OffsetDateTime.now(ZoneOffset.UTC)));

    client
        .post()
        .uri("/inspector/messages/" + seeded.getId() + "/replay")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.messageId")
        .isEqualTo(seeded.getId().toString())
        .jsonPath("$.previousStatus")
        .isEqualTo("RECEIVED")
        .jsonPath("$.newStatus")
        .isEqualTo("PERSISTED")
        .jsonPath("$.replayedAtUtc")
        .exists()
        .jsonPath("$.correlationId")
        .exists();
  }

  @Test
  void replayOfStillInvalidBodyReturns422RevalidationFailed() {
    InboundMessage seeded =
        inboundMessages.save(
            InboundMessageSeed.row(
                Hl7Fixtures.MISSING_PID,
                "ADMIT_SYSTEM",
                "REPLAY_BAD_1",
                InboundMessageStatus.FAILED,
                OffsetDateTime.now(ZoneOffset.UTC)));

    client
        .post()
        .uri("/inspector/messages/" + seeded.getId() + "/replay")
        .exchange()
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.error.code")
        .isEqualTo("REPLAY_REVALIDATION_FAILED")
        .jsonPath("$.error.correlationId")
        .exists();
  }

  @Test
  void replayOfUnknownMessageIdReturns404() {
    client
        .post()
        .uri("/inspector/messages/00000000-0000-0000-0000-000000000000/replay")
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectBody()
        .jsonPath("$.error.code")
        .isEqualTo("INSPECTOR_MESSAGE_NOT_FOUND");
  }
}
