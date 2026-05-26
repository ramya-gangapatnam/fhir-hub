package io.github.ramyagangapatnam.fhirhub.observability;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.ramyagangapatnam.fhirhub.testsupport.AbstractDbIntegrationTest;
import io.github.ramyagangapatnam.fhirhub.testsupport.Hl7Fixtures;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * T025 — Integration test for PHI-not-in-logs (SC-005, Principle I — NON-NEGOTIABLE).
 *
 * <p>Replays every US1 fixture through the running stack and asserts no PHI token from the curated
 * list leaks into the captured Logback output. The capture point is the {@code
 * io.github.ramyagangapatnam.fhirhub} logger so it sees every log line emitted by application code
 * (the masking converter from T014 runs at the appender boundary, so the captured event message
 * MUST already be redacted).
 *
 * <p>OpenTelemetry traces are exported through {@link PhiAttributeSanitizer} (T015) before they
 * leave the JVM. Because tests run with {@code fhir-hub.otel.exporter=none} by default, the SC-005
 * trace-redaction surface is covered by {@code PhiAttributeSanitizer}'s own unit test in the
 * observability package — this integration test concentrates on the log path, which is the surface
 * SC-005 explicitly names.
 *
 * <p>Expected to fail until the full ingestion pipeline (T028–T041) is in place — until then the
 * application never reaches the code paths that log "ingest.received" / "hl7.validate.fail" /
 * "fhir.persist.ok", and the redaction surface is not exercised end-to-end.
 */
class PhiRedactionIntegrationTest extends AbstractDbIntegrationTest {

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
  void replayingAllUs1FixturesProducesZeroPhiHitsInLogs() {
    postFixture(Hl7Fixtures.GOOD);
    postFixture(Hl7Fixtures.MISSING_PID);
    postFixture(Hl7Fixtures.BAD_FRAMING);
    postFixture(Hl7Fixtures.WRONG_VERSION);

    String formatted = renderEvents(capture.list);

    for (String token : Hl7Fixtures.PHI_TOKENS) {
      assertThat(formatted)
          .as("Captured log output must not contain PHI token <%s>", token)
          .doesNotContain(token);
    }
    // The redaction must have actually run somewhere in the pipeline — at least one [REDACTED-*]
    // marker should appear, otherwise the masker is silently inactive.
    assertThat(formatted).contains("[REDACTED-");
  }

  private static void postFixture(String hl7) {
    given()
        .header("Authorization", "Bearer " + VALID_TOKEN)
        .header("Content-Type", "application/hl7-v2")
        .body(hl7)
        .when()
        .post("/ingest/hl7v2");
  }

  private static String renderEvents(List<ILoggingEvent> events) {
    StringBuilder sb = new StringBuilder();
    for (ILoggingEvent ev : events) {
      sb.append(ev.getFormattedMessage()).append('\n');
      if (ev.getThrowableProxy() != null) {
        sb.append(ev.getThrowableProxy().getMessage()).append('\n');
      }
    }
    return sb.toString();
  }
}
