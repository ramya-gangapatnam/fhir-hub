package io.github.ramyagangapatnam.fhirhub.inspector;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ramyagangapatnam.fhirhub.persistence.InboundMessageRepository;
import io.github.ramyagangapatnam.fhirhub.persistence.InboundMessageStatus;
import io.github.ramyagangapatnam.fhirhub.testsupport.AbstractInspectorWebTest;
import io.github.ramyagangapatnam.fhirhub.testsupport.Hl7Fixtures;
import io.github.ramyagangapatnam.fhirhub.testsupport.InboundMessageSeed;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * T042 — Contract test for {@code GET /inspector/messages}.
 *
 * <p>Driven from {@code specs/001-adt-a01-ingestion-inspection/contracts/inspector.openapi.yaml}.
 * Asserts:
 *
 * <ul>
 *   <li>default ordering by {@code received_at_utc} descending;
 *   <li>{@code status} filter — single value and repeated values (form/explode);
 *   <li>{@code msh10} exact-match search;
 *   <li>{@code limit}/{@code offset} pagination with a correct {@code page.total};
 *   <li>the list view is metadata-only — it NEVER echoes the raw HL7 body (and therefore no PHI).
 * </ul>
 *
 * <p>Per Principle IV this was written and observed to fail (404, no handler) before T046.
 */
class InspectorListContractTest extends AbstractInspectorWebTest {

  @Autowired private InboundMessageRepository inboundMessages;

  private static final OffsetDateTime BASE =
      OffsetDateTime.of(2026, 5, 20, 14, 0, 0, 0, ZoneOffset.UTC);

  @BeforeEach
  void seedMessages() {
    // Four messages with distinct received-at instants so the desc ordering is observable.
    inboundMessages.save(
        InboundMessageSeed.row(
            Hl7Fixtures.GOOD, "ADMIT_A", "LIST0001", InboundMessageStatus.PERSISTED, BASE));
    inboundMessages.save(
        InboundMessageSeed.row(
            Hl7Fixtures.GOOD,
            "ADMIT_B",
            "LIST0002",
            InboundMessageStatus.FAILED,
            BASE.plusMinutes(1)));
    inboundMessages.save(
        InboundMessageSeed.row(
            Hl7Fixtures.GOOD,
            "ADMIT_C",
            "LIST0003",
            InboundMessageStatus.PERSISTED,
            BASE.plusMinutes(2)));
    inboundMessages.save(
        InboundMessageSeed.row(
            Hl7Fixtures.GOOD,
            "ADMIT_D",
            "LIST0004",
            InboundMessageStatus.RECEIVED,
            BASE.plusMinutes(3)));
  }

  @Test
  void listReturnsAllMessagesOrderedByReceivedAtDescWithDefaultPage() {
    client
        .get()
        .uri("/inspector/messages")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.page.total")
        .isEqualTo(4)
        .jsonPath("$.page.limit")
        .isEqualTo(50)
        .jsonPath("$.page.offset")
        .isEqualTo(0)
        .jsonPath("$.messages.length()")
        .isEqualTo(4)
        // received_at desc → most recent (LIST0004) first, oldest (LIST0001) last.
        .jsonPath("$.messages[0].msh10ControlId")
        .isEqualTo("LIST0004")
        .jsonPath("$.messages[0].sendingApplication")
        .isEqualTo("ADMIT_D")
        .jsonPath("$.messages[0].status")
        .isEqualTo("RECEIVED")
        .jsonPath("$.messages[3].msh10ControlId")
        .isEqualTo("LIST0001");
  }

  @Test
  void singleStatusFilterReturnsOnlyMatchingMessages() {
    client
        .get()
        .uri(b -> b.path("/inspector/messages").queryParam("status", "FAILED").build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.page.total")
        .isEqualTo(1)
        .jsonPath("$.messages.length()")
        .isEqualTo(1)
        .jsonPath("$.messages[0].msh10ControlId")
        .isEqualTo("LIST0002")
        .jsonPath("$.messages[0].status")
        .isEqualTo("FAILED");
  }

  @Test
  void repeatedStatusFilterReturnsTheUnionAndExcludesOthers() {
    client
        .get()
        .uri(b -> b.path("/inspector/messages").queryParam("status", "PERSISTED", "FAILED").build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        // PERSISTED (LIST0001, LIST0003) + FAILED (LIST0002) = 3; RECEIVED (LIST0004) excluded.
        .jsonPath("$.page.total")
        .isEqualTo(3)
        .jsonPath("$.messages.length()")
        .isEqualTo(3)
        .jsonPath("$.messages[0].msh10ControlId")
        .isEqualTo("LIST0003");
  }

  @Test
  void msh10ExactMatchSearchReturnsTheSingleMatch() {
    client
        .get()
        .uri(b -> b.path("/inspector/messages").queryParam("msh10", "LIST0002").build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.page.total")
        .isEqualTo(1)
        .jsonPath("$.messages.length()")
        .isEqualTo(1)
        .jsonPath("$.messages[0].msh10ControlId")
        .isEqualTo("LIST0002");
  }

  @Test
  void paginationHonoursLimitAndOffsetWhilePreservingTotal() {
    // First page of 2: LIST0004, LIST0003.
    client
        .get()
        .uri(
            b ->
                b.path("/inspector/messages")
                    .queryParam("limit", 2)
                    .queryParam("offset", 0)
                    .build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.page.total")
        .isEqualTo(4)
        .jsonPath("$.page.limit")
        .isEqualTo(2)
        .jsonPath("$.page.offset")
        .isEqualTo(0)
        .jsonPath("$.messages.length()")
        .isEqualTo(2)
        .jsonPath("$.messages[0].msh10ControlId")
        .isEqualTo("LIST0004")
        .jsonPath("$.messages[1].msh10ControlId")
        .isEqualTo("LIST0003");

    // Second page of 2: LIST0002, LIST0001.
    client
        .get()
        .uri(
            b ->
                b.path("/inspector/messages")
                    .queryParam("limit", 2)
                    .queryParam("offset", 2)
                    .build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.page.total")
        .isEqualTo(4)
        .jsonPath("$.messages.length()")
        .isEqualTo(2)
        .jsonPath("$.messages[0].msh10ControlId")
        .isEqualTo("LIST0002")
        .jsonPath("$.messages[1].msh10ControlId")
        .isEqualTo("LIST0001");
  }

  @Test
  void listViewNeverReturnsRawHl7OrPhi() {
    String body =
        new String(
            client
                .get()
                .uri("/inspector/messages")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBodyContent());

    assertThat(body).doesNotContain("rawHl7");
    for (String phi : Hl7Fixtures.PHI_TOKENS) {
      assertThat(body)
          .as("List view must not leak PHI token <%s> from the raw HL7 body", phi)
          .doesNotContain(phi);
    }
  }
}
