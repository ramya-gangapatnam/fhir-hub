package io.github.ramyagangapatnam.fhirhub.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * SC-005 enforcement: representative HL7 input MUST produce log output containing zero PHI tokens.
 *
 * <p>The test wires a Logback {@link PatternLayoutEncoder} whose {@code %msg} / {@code %m}
 * conversion is bound to {@link MaskingConverter} (the same binding as {@code logback-spring.xml})
 * and captures the bytes that would leave the JVM. The synthetic ADT^A01 message contains every
 * PHI shape relevant to the spec: patient name, MRN, DOB (YYYYMMDD), street address, phone, SSN,
 * and email. None of those tokens may appear in the captured output.
 *
 * <p>Principle I (PHI Confidentiality in Logs — NON-NEGOTIABLE).
 */
class PhiLogMaskingTest {

  private static final String TEST_LOGGER_NAME = "phi.masking.test." + System.nanoTime();

  /**
   * Representative synthetic ADT^A01 with a wide assortment of PHI tokens. Every field value here
   * is invented — no real person is described. The test asserts none of these tokens leak.
   */
  private static final String REPRESENTATIVE_HL7 =
      "MSH|^~\\&|ADMIT_SYSTEM|HOSPITAL|RECEIVER|FACILITY|20260520143210||ADT^A01|MSG00001|P|2.5\r"
          + "EVN|A01|20260520143210\r"
          + "PID|1||MRN0001234^^^HOSP^MR||DOEPATIENT^JANE^ELIZABETH||19850203|F|||"
          + "742 EVERGREEN TERRACE^^SPRINGFIELD^IL^62704^USA||(555)555-0142||EN|S|NON|"
          + "123-45-6789|||||||||||||\r"
          + "NK1|1|DOEPATIENT^JOHN|SPO||(555)555-0143||janedoe@example.invalid\r"
          + "PV1|1|I|2W^201^A^GEN|R||||DR_SMITH^WELBY^MARCUS|||||||||||||||||||||||||||||"
          + "||||||20260520143210";

  private static final List<String> PHI_TOKENS =
      List.of(
          "DOEPATIENT",
          "JANE",
          "ELIZABETH",
          "MRN0001234",
          "19850203",
          "742 EVERGREEN TERRACE",
          "SPRINGFIELD",
          "62704",
          "(555)555-0142",
          "(555)555-0143",
          "123-45-6789",
          "janedoe@example.invalid",
          "DR_SMITH",
          "WELBY");

  private LoggerContext context;
  private Logger testLogger;
  private OutputStreamAppender<ILoggingEvent> appender;
  private ByteArrayOutputStream captured;

  @AfterEach
  void tearDown() {
    if (appender != null) {
      appender.stop();
    }
    if (testLogger != null) {
      testLogger.detachAndStopAllAppenders();
    }
  }

  @Test
  void rawHl7LoggedAtInfoIsFullyMasked() {
    setupLogger("%msg");

    org.slf4j.Logger slf4j = LoggerFactory.getLogger(TEST_LOGGER_NAME);
    slf4j.info("Incoming HL7 body: {}", REPRESENTATIVE_HL7);

    String output = captured.toString(StandardCharsets.UTF_8);

    assertNoPhiTokensIn(output);
    assertThat(output).contains("[REDACTED-HL7]");
  }

  @Test
  void hl7TokensConcatenatedIntoMessageAreMasked() {
    setupLogger("%msg");

    org.slf4j.Logger slf4j = LoggerFactory.getLogger(TEST_LOGGER_NAME);
    // Simulates a code path that accidentally interpolates raw fields without a placeholder.
    slf4j.info(
        "Parsed PID line: PID|1||MRN0001234^^^HOSP^MR||DOEPATIENT^JANE^ELIZABETH||19850203|F");

    String output = captured.toString(StandardCharsets.UTF_8);
    assertNoPhiTokensIn(output);
  }

  @Test
  void dobAndIdentifierPatternsAreMaskedEvenOutsideHl7Segments() {
    setupLogger("%msg");

    org.slf4j.Logger slf4j = LoggerFactory.getLogger(TEST_LOGGER_NAME);
    slf4j.info("DOB={} SSN={} phone={}", "19850203", "123-45-6789", "(555)555-0142");

    String output = captured.toString(StandardCharsets.UTF_8);
    assertThat(output).doesNotContain("19850203");
    assertThat(output).doesNotContain("123-45-6789");
    assertThat(output).doesNotContain("(555)555-0142");
  }

  @Test
  void exceptionMessageContainingHl7IsAlsoMasked() {
    setupLogger("%msg");

    org.slf4j.Logger slf4j = LoggerFactory.getLogger(TEST_LOGGER_NAME);
    slf4j.error("Validation failed: {}", new RuntimeException(REPRESENTATIVE_HL7).getMessage());

    String output = captured.toString(StandardCharsets.UTF_8);
    assertNoPhiTokensIn(output);
  }

  private void setupLogger(String pattern) {
    context = (LoggerContext) LoggerFactory.getILoggerFactory();

    PatternLayout layout = new PatternLayout();
    layout.setContext(context);
    layout.getInstanceConverterMap().put("msg", MaskingConverter.class.getName());
    layout.getInstanceConverterMap().put("m", MaskingConverter.class.getName());
    layout.setPattern(pattern + "%n");
    layout.start();

    LayoutWrappingEncoder<ILoggingEvent> encoder = new LayoutWrappingEncoder<>();
    encoder.setContext(context);
    encoder.setLayout(layout);
    encoder.start();

    captured = new ByteArrayOutputStream();
    appender = new OutputStreamAppender<>();
    appender.setContext(context);
    appender.setOutputStream(captured);
    appender.setEncoder(encoder);
    appender.setImmediateFlush(true);
    appender.start();

    testLogger = context.getLogger(TEST_LOGGER_NAME);
    testLogger.setAdditive(false);
    testLogger.setLevel(Level.DEBUG);
    testLogger.detachAndStopAllAppenders();
    testLogger.addAppender(appender);
  }

  private static void assertNoPhiTokensIn(String output) {
    for (String token : PHI_TOKENS) {
      assertThat(output)
          .as("PHI token %s leaked into log output", token)
          .doesNotContain(token);
    }
    // The redaction must have actually run — at least one redaction marker should be present.
    assertThat(output).matches("(?s).*\\[REDACTED-.*");
  }
}
