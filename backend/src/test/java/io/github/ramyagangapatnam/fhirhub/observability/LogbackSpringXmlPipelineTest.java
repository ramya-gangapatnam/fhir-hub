package io.github.ramyagangapatnam.fhirhub.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.joran.spi.JoranException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ramyagangapatnam.fhirhub.testsupport.Hl7Fixtures;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * End-to-end verification of the production {@code logback-spring.xml} pipeline.
 *
 * <p>Applies the real production config (not a programmatic in-Java config) to the SLF4J-bound
 * {@link LoggerContext} via Logback's {@link JoranConfigurator}, attaches a capturing {@link
 * OutputStreamAppender} that reuses the production {@code STDOUT} appender's pattern, and emits a
 * few log events that include PHI tokens and a literal double-quote character. The test then
 * asserts:
 *
 * <ol>
 *   <li>Every emitted line is a well-formed JSON object that Jackson can parse.
 *   <li>The {@code msg} field is the MaskingConverter's output — {@code [REDACTED-*]} markers are
 *       present.
 *   <li>No PHI token from the curated fixture list appears anywhere in the captured stream.
 *   <li>A literal {@code "} in the original log message reaches the JSON {@code msg} field as
 *       {@code \"} (proving the {@code %replace} JSON-escape stage runs <em>after</em> PHI masking
 *       and does not mangle the masked text).
 * </ol>
 *
 * <p>This test fails if either stage of the pipeline regresses:
 *
 * <ul>
 *   <li>If masking is bypassed → PHI tokens leak → assertion 3 fails.
 *   <li>If the JSON escape stage is broken (e.g., the original broken {@code %replace}) → the
 *       captured line is no longer valid JSON → assertion 1 fails.
 * </ul>
 *
 * <p>Principle I (PHI Confidentiality in Logs — NON-NEGOTIABLE).
 */
class LogbackSpringXmlPipelineTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private LoggerContext context;
  private OutputStreamAppender<ILoggingEvent> captureAppender;
  private Logger pipelineLogger;

  @AfterEach
  void detachCaptureAppender() {
    if (pipelineLogger != null && captureAppender != null) {
      pipelineLogger.detachAppender(captureAppender);
      captureAppender.stop();
    }
  }

  @Test
  void productionLogbackSpringXmlProducesMaskedWellFormedJsonLines() throws Exception {
    ByteArrayOutputStream captured = loadProductionConfigOntoSlf4jBoundContext();

    pipelineLogger =
        context.getLogger("io.github.ramyagangapatnam.fhirhub.observability.pipeline-test");
    pipelineLogger.setLevel(Level.INFO);
    pipelineLogger.addAppender(captureAppender);

    // Emit a couple of representative events that cover the three pipeline expectations.
    pipelineLogger.info("Incoming HL7 body: {}", Hl7Fixtures.GOOD);
    pipelineLogger.info("operator said \"hello\" while processing PID|1||MRN0001234");
    pipelineLogger.info("DOB={} SSN=123-45-6789", "19850203");

    String output = captured.toString(StandardCharsets.UTF_8);
    assertThat(output).as("Production logback config must emit at least one line").isNotEmpty();

    String[] lines = output.split("\\R");
    int parsedLines = 0;
    boolean sawRedactedMarker = false;
    boolean sawEscapedDoubleQuote = false;

    for (String line : lines) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      JsonNode node;
      try {
        node = JSON.readTree(trimmed);
      } catch (Exception parseFailure) {
        throw new AssertionError(
            "Captured line is not well-formed JSON — the %replace JSON-escape stage is broken.\n"
                + "Line: "
                + trimmed,
            parseFailure);
      }
      parsedLines++;
      assertThat(node.has("msg")).as("Each JSON event must carry a `msg` field").isTrue();
      String msg = node.get("msg").asText();
      if (msg.contains("[REDACTED-")) {
        sawRedactedMarker = true;
      }
      // The original log carried a literal `"` around the word hello; verify that the
      // %replace stage produced the JSON-escaped form `\"` in the on-wire text. Jackson
      // returns the decoded value, so we check the raw line for the byte sequence \\".
      if (trimmed.contains("\\\"hello\\\"")) {
        sawEscapedDoubleQuote = true;
      }
    }

    assertThat(parsedLines)
        .as("At least three log events were emitted; all must be well-formed JSON")
        .isGreaterThanOrEqualTo(3);
    assertThat(sawRedactedMarker)
        .as("Captured output must contain a [REDACTED-*] marker — the MaskingConverter ran")
        .isTrue();
    assertThat(sawEscapedDoubleQuote)
        .as(
            "Literal `\"` in the source message must be emitted as `\\\"` in the wire JSON "
                + "(proves the %replace stage runs and is not mangling the masked text)")
        .isTrue();

    for (String token : Hl7Fixtures.PHI_TOKENS) {
      assertThat(output)
          .as("Captured wire output must not contain PHI token <%s>", token)
          .doesNotContain(token);
    }
  }

  /**
   * Loads {@code src/main/resources/logback-spring.xml} via Joran onto the SLF4J-bound {@link
   * LoggerContext} (the one the rest of the JVM already routes through). Builds a fresh {@link
   * PatternLayoutEncoder} that uses the SAME pattern the production {@code STDOUT} appender just
   * registered, then attaches a capturing {@link OutputStreamAppender} that the test will
   * subsequently attach directly to the logger under test.
   *
   * <p>Returns the {@link ByteArrayOutputStream} that holds the captured bytes.
   */
  private ByteArrayOutputStream loadProductionConfigOntoSlf4jBoundContext() throws JoranException {
    context = (LoggerContext) LoggerFactory.getILoggerFactory();
    // Reset and reapply the production config so the test starts from a known baseline.
    context.reset();

    JoranConfigurator configurator = new JoranConfigurator();
    configurator.setContext(context);
    try (InputStream stream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream("logback-spring.xml")) {
      if (stream == null) {
        throw new IllegalStateException("logback-spring.xml not found on test classpath");
      }
      configurator.doConfigure(stream);
    } catch (java.io.IOException ioe) {
      throw new IllegalStateException("Failed to read logback-spring.xml", ioe);
    }

    Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);

    @SuppressWarnings("unchecked")
    OutputStreamAppender<ILoggingEvent> productionStdout =
        (OutputStreamAppender<ILoggingEvent>) rootLogger.getAppender("STDOUT");
    if (productionStdout == null) {
      throw new IllegalStateException(
          "Production logback-spring.xml is expected to declare an appender named STDOUT");
    }
    PatternLayoutEncoder productionEncoder = (PatternLayoutEncoder) productionStdout.getEncoder();
    String productionPattern = productionEncoder.getPattern();

    PatternLayoutEncoder captureEncoder = new PatternLayoutEncoder();
    captureEncoder.setContext(context);
    captureEncoder.setPattern(productionPattern);
    captureEncoder.start();

    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    captureAppender = new OutputStreamAppender<>();
    captureAppender.setContext(context);
    captureAppender.setName("TEST_CAPTURE");
    captureAppender.setEncoder(captureEncoder);
    captureAppender.setOutputStream(captured);
    captureAppender.setImmediateFlush(true);
    captureAppender.start();

    return captured;
  }
}
