package io.github.ramyagangapatnam.fhirhub.audit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ramyagangapatnam.fhirhub.testsupport.AbstractDbIntegrationTest;
import io.github.ramyagangapatnam.fhirhub.testsupport.Hl7Fixtures;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * T026 — Integration test for audit emission (SC-006, FR-016, Principle II — NON-NEGOTIABLE).
 *
 * <p>Drives the full ingestion + FHIR-read happy path and asserts:
 *
 * <ul>
 *   <li>Exactly one {@code create} audit event is written per FHIR resource (one Patient, one
 *       Encounter) — FR-016.
 *   <li>Exactly one {@code read} audit event is written per FHIR REST GET (the Patient GET and the
 *       Encounter GET).
 *   <li>Each event conforms to {@code contracts/audit-event.schema.json}: required keys present,
 *       {@code schemaVersion = "1"}, {@code operation} and {@code outcome} from the enum, {@code
 *       actor.type} ∈ {operator, system}, {@code resource.type} ∈ {InboundMessage, Patient,
 *       Encounter, AuditEvent}, UUIDs where the schema asks for UUIDs.
 *   <li>Zero PHI tokens from the curated list appear in any audit JSONL line.
 * </ul>
 *
 * <p>Expected to fail until T037 + T038 + T039 land.
 */
class AuditEmissionIntegrationTest extends AbstractDbIntegrationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String UUID_REGEX =
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
  private static final Set<String> ALLOWED_OPERATIONS =
      Set.of("create", "read", "update", "replay", "delete");
  private static final Set<String> ALLOWED_OUTCOMES = Set.of("success", "failure");
  private static final Set<String> ALLOWED_ACTOR_TYPES = Set.of("operator", "system");
  private static final Set<String> ALLOWED_RESOURCE_TYPES =
      Set.of("InboundMessage", "Patient", "Encounter", "AuditEvent");

  @TempDir static Path auditDir;

  @DynamicPropertySource
  static void auditProperties(DynamicPropertyRegistry registry) {
    registry.add("fhir-hub.audit.sink", () -> "file");
    registry.add("fhir-hub.audit.file.path", () -> auditDir.resolve("audit.log").toString());
  }

  @Test
  void ingestPlusFhirReadEmitsExpectedAuditEvents() throws Exception {
    Response ingest =
        given()
            .header("Authorization", "Bearer " + VALID_TOKEN)
            .header("Content-Type", "application/hl7-v2")
            .body(Hl7Fixtures.GOOD)
            .when()
            .post("/ingest/hl7v2");
    ingest.then().statusCode(202);
    String messageId = ingest.jsonPath().getString("messageId");

    String[] ids = pollInspectorForResourceIds(messageId);
    String patientId = ids[0];
    String encounterId = ids[1];

    given()
        .header("Authorization", "Bearer " + VALID_TOKEN)
        .header("Accept", "application/fhir+json")
        .when()
        .get("/fhir/Patient/" + patientId)
        .then()
        .statusCode(200);

    given()
        .header("Authorization", "Bearer " + VALID_TOKEN)
        .header("Accept", "application/fhir+json")
        .when()
        .get("/fhir/Encounter/" + encounterId)
        .then()
        .statusCode(200);

    Path auditLog = auditDir.resolve("audit.log");
    waitForFile(auditLog, 5_000);
    List<JsonNode> events = readJsonl(auditLog);

    assertThat(events).isNotEmpty();

    for (JsonNode event : events) {
      assertSchemaConformant(event);
      assertNoPhi(event.toString());
    }

    long createPatient = countEvents(events, "create", "Patient", patientId);
    long createEncounter = countEvents(events, "create", "Encounter", encounterId);
    long readPatient = countEvents(events, "read", "Patient", patientId);
    long readEncounter = countEvents(events, "read", "Encounter", encounterId);

    assertThat(createPatient)
        .as("Exactly one create audit event per Patient ingestion (FR-016)")
        .isEqualTo(1);
    assertThat(createEncounter)
        .as("Exactly one create audit event per Encounter ingestion (FR-016)")
        .isEqualTo(1);
    assertThat(readPatient).as("Exactly one read audit event per Patient REST GET").isEqualTo(1);
    assertThat(readEncounter)
        .as("Exactly one read audit event per Encounter REST GET")
        .isEqualTo(1);
  }

  private static String[] pollInspectorForResourceIds(String messageId) {
    long deadline = System.currentTimeMillis() + 5_000;
    while (System.currentTimeMillis() < deadline) {
      Response detail =
          RestAssured.given()
              .header("Authorization", "Bearer " + VALID_TOKEN)
              .when()
              .get("/inspector/messages/" + messageId);
      if (detail.statusCode() == 200) {
        JsonPath body = detail.jsonPath();
        String patientId = body.getString("fhirResources.patient.id");
        String encounterId = body.getString("fhirResources.encounter.id");
        if (patientId != null && encounterId != null) {
          return new String[] {patientId, encounterId};
        }
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    throw new AssertionError("Resources never became available for messageId=" + messageId);
  }

  private static void waitForFile(Path file, long timeoutMs) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (Files.exists(file) && Files.size(file) > 0) {
        return;
      }
      Thread.sleep(100);
    }
  }

  private static List<JsonNode> readJsonl(Path file) throws Exception {
    List<JsonNode> out = new ArrayList<>();
    if (!Files.exists(file)) {
      return out;
    }
    for (String line : Files.readAllLines(file)) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      out.add(MAPPER.readTree(trimmed));
    }
    return out;
  }

  private static long countEvents(
      List<JsonNode> events, String operation, String resourceType, String resourceId) {
    return events.stream()
        .filter(e -> operation.equals(text(e, "operation")))
        .filter(e -> resourceType.equals(text(e.path("resource"), "type")))
        .filter(e -> resourceId.equals(text(e.path("resource"), "id")))
        .count();
  }

  private static String text(JsonNode parent, String field) {
    JsonNode n = parent.path(field);
    return n.isMissingNode() || n.isNull() ? null : n.asText();
  }

  private static void assertSchemaConformant(JsonNode event) {
    for (String required :
        List.of(
            "auditId",
            "schemaVersion",
            "timestampUtc",
            "actor",
            "correlationId",
            "resource",
            "operation",
            "outcome")) {
      assertThat(event.has(required))
          .as("Audit event must contain required field '%s' (audit-event.schema.json)", required)
          .isTrue();
    }
    assertThat(text(event, "auditId")).matches(UUID_REGEX);
    assertThat(text(event, "correlationId")).matches(UUID_REGEX);
    assertThat(text(event, "schemaVersion")).isEqualTo("1");
    assertThat(ALLOWED_OPERATIONS).contains(text(event, "operation"));
    assertThat(ALLOWED_OUTCOMES).contains(text(event, "outcome"));
    assertThat(ALLOWED_ACTOR_TYPES).contains(text(event.path("actor"), "type"));
    assertThat(text(event.path("actor"), "identity")).isNotBlank();
    assertThat(ALLOWED_RESOURCE_TYPES).contains(text(event.path("resource"), "type"));
    assertThat(text(event.path("resource"), "id")).isNotBlank();
    if ("failure".equals(text(event, "outcome"))) {
      assertThat(event.has("failureReason"))
          .as("failureReason is required when outcome=failure")
          .isTrue();
      assertThat(text(event.path("failureReason"), "code")).isNotBlank();
      assertThat(text(event.path("failureReason"), "summary")).isNotBlank();
    }
    // Spot-check that ids parse as UUIDs where the source produced them (resource ids and
    // correlation ids are UUIDs in this demo).
    UUID.fromString(text(event, "auditId"));
    UUID.fromString(text(event, "correlationId"));
  }

  private static void assertNoPhi(String body) {
    for (String token : Hl7Fixtures.PHI_TOKENS) {
      assertThat(body)
          .as("Audit event body must not contain PHI token <%s>", token)
          .doesNotContain(token);
    }
  }
}
