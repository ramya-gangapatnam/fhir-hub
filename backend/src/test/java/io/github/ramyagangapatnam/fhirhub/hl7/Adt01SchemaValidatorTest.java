package io.github.ramyagangapatnam.fhirhub.hl7;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ramyagangapatnam.fhirhub.error.ErrorCode;
import io.github.ramyagangapatnam.fhirhub.hl7.Adt01SchemaValidator.Issue;
import io.github.ramyagangapatnam.fhirhub.hl7.Adt01SchemaValidator.ValidationResult;
import io.github.ramyagangapatnam.fhirhub.testsupport.Hl7Fixtures;
import org.junit.jupiter.api.Test;

/**
 * T021 — Unit test for the ADT^A01 schema validator.
 *
 * <p>Per plan.md §3.1 + contracts/error-codes.md, the validator MUST recognise:
 *
 * <ul>
 *   <li>MSH, PID, PV1 presence (missing → {@link ErrorCode#HL7_PARSE_MISSING_SEGMENT}).
 *   <li>MSH-9 = {@code ADT^A01} (other message types → {@link
 *       ErrorCode#HL7_PARSE_UNSUPPORTED_MESSAGE_TYPE}).
 *   <li>MSH-12 ∈ {2.5, 2.5.1} (other versions → {@link ErrorCode#HL7_PARSE_UNSUPPORTED_VERSION}).
 *   <li>Broken framing (missing segment terminators / wrong field separator) → {@link
 *       ErrorCode#HL7_PARSE_INVALID_FRAMING}.
 * </ul>
 *
 * <p>The {@code location} in each {@link Issue} is a structural reference like {@code PID(missing)}
 * or {@code MSH-12} — never a field value (Principle I).
 *
 * <p>Expected to fail with {@link UnsupportedOperationException} until T030 lands.
 */
class Adt01SchemaValidatorTest {

  private final Adt01SchemaValidator validator = new Adt01SchemaValidator();

  @Test
  void goodFixtureIsValid() {
    ValidationResult result = validator.validate(Hl7Fixtures.GOOD);

    assertThat(result.isValid()).as("GOOD fixture must pass schema validation").isTrue();
    assertThat(result.issues()).isEmpty();
  }

  @Test
  void missingPidProducesMissingSegmentError() {
    ValidationResult result = validator.validate(Hl7Fixtures.MISSING_PID);

    assertThat(result.isValid()).isFalse();
    assertThat(result.issues())
        .extracting(Issue::code)
        .contains(ErrorCode.HL7_PARSE_MISSING_SEGMENT);
    Issue pid =
        result.issues().stream()
            .filter(i -> i.code() == ErrorCode.HL7_PARSE_MISSING_SEGMENT)
            .findFirst()
            .orElseThrow();
    assertThat(pid.location())
        .as("location must reference the missing PID segment structurally, never a value")
        .containsIgnoringCase("PID");
  }

  @Test
  void badFramingProducesInvalidFramingError() {
    ValidationResult result = validator.validate(Hl7Fixtures.BAD_FRAMING);

    assertThat(result.isValid()).isFalse();
    assertThat(result.issues())
        .extracting(Issue::code)
        .contains(ErrorCode.HL7_PARSE_INVALID_FRAMING);
  }

  @Test
  void unsupportedVersionProducesUnsupportedVersionError() {
    ValidationResult result = validator.validate(Hl7Fixtures.WRONG_VERSION);

    assertThat(result.isValid()).isFalse();
    assertThat(result.issues())
        .extracting(Issue::code)
        .contains(ErrorCode.HL7_PARSE_UNSUPPORTED_VERSION);
    Issue version =
        result.issues().stream()
            .filter(i -> i.code() == ErrorCode.HL7_PARSE_UNSUPPORTED_VERSION)
            .findFirst()
            .orElseThrow();
    assertThat(version.location())
        .as("Unsupported-version issue must point at MSH-12 structurally")
        .contains("MSH-12");
  }

  @Test
  void wrongMessageTypeProducesUnsupportedMessageTypeError() {
    String adtA04 = Hl7Fixtures.GOOD.replace("ADT^A01", "ADT^A04");

    ValidationResult result = validator.validate(adtA04);

    assertThat(result.isValid()).isFalse();
    assertThat(result.issues())
        .extracting(Issue::code)
        .contains(ErrorCode.HL7_PARSE_UNSUPPORTED_MESSAGE_TYPE);
  }

  @Test
  void issueLocationFieldsNeverEchoSourceValues() {
    ValidationResult result = validator.validate(Hl7Fixtures.MISSING_PID);

    for (Issue issue : result.issues()) {
      for (String token : Hl7Fixtures.PHI_TOKENS) {
        assertThat(issue.location() == null ? "" : issue.location())
            .as("Issue.location must not echo PHI token <%s>", token)
            .doesNotContain(token);
      }
    }
  }
}
