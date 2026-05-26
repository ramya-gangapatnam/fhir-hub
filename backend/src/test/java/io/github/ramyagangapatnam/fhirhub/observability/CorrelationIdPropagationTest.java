package io.github.ramyagangapatnam.fhirhub.observability;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ramyagangapatnam.fhirhub.testsupport.AbstractDbIntegrationTest;
import io.github.ramyagangapatnam.fhirhub.testsupport.Hl7Fixtures;
import io.restassured.response.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * T027 — Integration test for correlation-ID propagation (SC-009, Principle VII).
 *
 * <p>POSTs a fixture with a fixed {@code X-Correlation-Id}. Asserts the same id appears in:
 *
 * <ul>
 *   <li>The response {@code X-Correlation-Id} header (round-trip).
 *   <li>The response body's {@code correlationId} field.
 *   <li>{@code inbound_message.correlation_id} column for the persisted row.
 *   <li>Every captured log line emitted by application code (via the MDC {@code correlation_id}
 *       key).
 *   <li>The audit event written for the inbound message (JSONL audit sink).
 * </ul>
 *
 * <p>The W3C traceparent span-level propagation surface is covered by {@code ObservabilityConfig}'s
 * own startup wiring and the {@link PhiAttributeSanitizer} unit tests; integration coverage of the
 * span side requires an OTel collector and is documented as out-of-scope for the demo per Principle
 * X. The five anchors above are the SC-009 surface.
 *
 * <p>Expected to fail until the full ingestion pipeline (T028-T041) is in place.
 */
class CorrelationIdPropagationTest extends AbstractDbIntegrationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String FIXED_CORRELATION_ID = "abcdef01-2345-6789-abcd-ef0123456789";

  @TempDir static Path auditDir;

  @DynamicPropertySource
  static void auditProperties(DynamicPropertyRegistry registry) {
    registry.add("fhir-hub.audit.sink", () -> "file");
    registry.add("fhir-hub.audit.file.path", () -> auditDir.resolve("audit.log").toString());
  }

  private LoggerContext context;
  private ListAppender<ILoggingEvent> capture;
  private ch.qos.logback.classic.Logger appLogger;

  @BeforeEach
  void attachCapturingAppender() {
    context = (LoggerContext) LoggerFactory.getILoggerFactory();
    appLogger = context.getLogger("io.github.ramyagangapatnam.fhirhub");
    appLogger.setLevel(Level.DEBUG);
    capture = new ListAppender<>();
    capture.setContext(context);
    capture.start();
    appLogger.addAppender(capture);
  }

  @AfterEach
  void detachCapturingAppender() {
    if (appLogger != null && capture != null) {
      appLogger.detachAppender(capture);
      capture.stop();
    }
  }

  @Test
  void correlationIdPropagatesAcrossHttpDatabaseLogsAndAudit() throws Exception {
    Response response =
        given()
            .header("Authorization", "Bearer " + VALID_TOKEN)
            .header("Content-Type", "application/hl7-v2")
            .header("X-Correlation-Id", FIXED_CORRELATION_ID)
            .body(Hl7Fixtures.GOOD)
            .when()
            .post("/ingest/hl7v2");

    response.then().statusCode(202);
    assertThat(response.header("X-Correlation-Id"))
        .as("Response header echo (SC-009 step 1)")
        .isEqualTo(FIXED_CORRELATION_ID);
    assertThat(response.jsonPath().getString("correlationId"))
        .as("Response body correlationId (SC-009 step 2)")
        .isEqualTo(FIXED_CORRELATION_ID);

    String messageId = response.jsonPath().getString("messageId");

    String persisted = pollInboundMessageCorrelationId(messageId, 5_000);
    assertThat(persisted)
        .as("inbound_message.correlation_id column (SC-009 step 3)")
        .isEqualTo(FIXED_CORRELATION_ID);

    boolean foundInLogs =
        capture.list.stream()
            .map(ev -> ev.getMDCPropertyMap().get("correlation_id"))
            .anyMatch(FIXED_CORRELATION_ID::equals);
    assertThat(foundInLogs)
        .as(
            "At least one log line was bound to MDC.correlation_id=%s (SC-009 step 4)",
            FIXED_CORRELATION_ID)
        .isTrue();

    // No log line for THIS request may carry a different correlation id — the MDC binding must
    // be consistent across the whole request scope.
    boolean conflicting =
        capture.list.stream()
            .map(ev -> ev.getMDCPropertyMap().get("correlation_id"))
            .filter(java.util.Objects::nonNull)
            .anyMatch(id -> !id.equals(FIXED_CORRELATION_ID));
    assertThat(conflicting)
        .as("No captured log line may carry a different correlation_id during this request")
        .isFalse();

    Path auditLog = auditDir.resolve("audit.log");
    waitForFile(auditLog, 5_000);
    boolean foundInAudit = false;
    for (String line : Files.readAllLines(auditLog)) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      JsonNode event = MAPPER.readTree(trimmed);
      if (FIXED_CORRELATION_ID.equals(textOrNull(event, "correlationId"))) {
        foundInAudit = true;
        break;
      }
    }
    assertThat(foundInAudit)
        .as("At least one audit event carries the request's correlation id (SC-009 step 5)")
        .isTrue();
  }

  private static String textOrNull(JsonNode n, String field) {
    JsonNode v = n.path(field);
    return v.isMissingNode() || v.isNull() ? null : v.asText();
  }

  private static String pollInboundMessageCorrelationId(String messageId, long timeoutMs)
      throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      try (Connection c =
              DriverManager.getConnection(
                  POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
          PreparedStatement ps =
              c.prepareStatement("SELECT correlation_id FROM inbound_message WHERE id = ?")) {
        ps.setObject(1, java.util.UUID.fromString(messageId));
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            return rs.getString(1);
          }
        }
      } catch (Exception ignored) {
        // Table may not exist yet; loop until it does or timeout fires.
      }
      Thread.sleep(100);
    }
    return null;
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
}
