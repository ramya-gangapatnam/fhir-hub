package io.github.ramyagangapatnam.fhirhub.inspector;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ramyagangapatnam.fhirhub.testsupport.AbstractInspectorWebTest;
import io.github.ramyagangapatnam.fhirhub.testsupport.Hl7Fixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

/**
 * T045 — Integration test for replay idempotency + audit emission (Principle II, Principle VIII).
 *
 * <p>Ingests one good ADT^A01, waits for it to reach {@code PERSISTED}, then replays it 5 times and
 * asserts:
 *
 * <ul>
 *   <li>the Patient and Encounter row counts are unchanged after all 5 replays (idempotent —
 *       Principle VIII);
 *   <li>exactly 5 distinct {@code replay} audit events were written to the JSONL sink, one per
 *       replay (Principle II — every operator-initiated replay is audited);
 *   <li>each replay event carries {@code operation = replay} and the SAME {@code correlationId} as
 *       the originating ingestion — the contract this implementation pins (the replay reuses the
 *       inbound message's stored correlation id so the whole lineage shares one id, SC-009).
 * </ul>
 *
 * <p>Per Principle IV this was written and observed to fail before T048.
 */
class ReplayIdempotencyIntegrationTest extends AbstractInspectorWebTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int REPLAYS = 5;

  @TempDir static Path auditDir;

  @DynamicPropertySource
  static void auditProperties(DynamicPropertyRegistry registry) {
    registry.add("fhir-hub.audit.sink", () -> "file");
    registry.add("fhir-hub.audit.file.path", () -> auditDir.resolve("audit.log").toString());
  }

  @Test
  void replayingPersistedMessageFiveTimesIsIdempotentAndAuditsEachReplay() throws Exception {
    String ingestBody = ingest(Hl7Fixtures.GOOD);
    JsonNode ingested = MAPPER.readTree(ingestBody);
    String messageId = ingested.path("messageId").asText();
    String ingestCorrelationId = ingested.path("correlationId").asText();

    awaitStatus(messageId, "PERSISTED");

    long patientsBefore = countResources("Patient");
    long encountersBefore = countResources("Encounter");
    assertThat(patientsBefore).isEqualTo(1);
    assertThat(encountersBefore).isEqualTo(1);

    for (int i = 0; i < REPLAYS; i++) {
      client
          .post()
          .uri("/inspector/messages/" + messageId + "/replay")
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.newStatus")
          .isEqualTo("PERSISTED");
    }

    assertThat(countResources("Patient"))
        .as("Replay must not create new Patient rows (Principle VIII)")
        .isEqualTo(patientsBefore);
    assertThat(countResources("Encounter"))
        .as("Replay must not create new Encounter rows (Principle VIII)")
        .isEqualTo(encountersBefore);

    List<JsonNode> replayEvents = readReplayEvents();
    assertThat(replayEvents)
        .as("Exactly one replay audit event per replay (Principle II)")
        .hasSize(REPLAYS);

    Set<String> auditIds = new HashSet<>();
    for (JsonNode event : replayEvents) {
      assertThat(event.path("operation").asText()).isEqualTo("replay");
      assertThat(event.path("correlationId").asText())
          .as("Replay audit event reuses the originating ingestion correlation id (SC-009)")
          .isEqualTo(ingestCorrelationId);
      auditIds.add(event.path("auditId").asText());
    }
    assertThat(auditIds).as("Each replay emits a distinct auditId").hasSize(REPLAYS);
  }

  private String ingest(String body) {
    EntityExchangeResult<byte[]> res =
        client
            .post()
            .uri("/ingest/hl7v2")
            .header(HttpHeaders.CONTENT_TYPE, "application/hl7-v2")
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isAccepted()
            .expectBody()
            .returnResult();
    return new String(res.getResponseBodyContent());
  }

  private void awaitStatus(String messageId, String wantStatus) throws Exception {
    long deadline = System.currentTimeMillis() + 8_000;
    while (System.currentTimeMillis() < deadline) {
      EntityExchangeResult<byte[]> res =
          client
              .get()
              .uri("/inspector/messages/" + messageId)
              .exchange()
              .expectBody()
              .returnResult();
      if (res.getStatus().value() == 200
          && wantStatus.equals(
              MAPPER.readTree(res.getResponseBodyContent()).path("status").asText())) {
        return;
      }
      Thread.sleep(150);
    }
    throw new AssertionError("Message " + messageId + " never reached " + wantStatus);
  }

  private long countResources(String resourceType) throws Exception {
    try (Connection c =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT count(*) FROM fhir_resource WHERE resource_type = '"
                    + resourceType
                    + "'")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private List<JsonNode> readReplayEvents() throws Exception {
    Path auditLog = auditDir.resolve("audit.log");
    List<JsonNode> events = new ArrayList<>();
    if (!Files.exists(auditLog)) {
      return events;
    }
    for (String line : Files.readAllLines(auditLog)) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      JsonNode node = MAPPER.readTree(trimmed);
      if ("replay".equals(node.path("operation").asText())) {
        events.add(node);
      }
    }
    return events;
  }
}
