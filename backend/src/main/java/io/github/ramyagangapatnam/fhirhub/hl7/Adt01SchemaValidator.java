package io.github.ramyagangapatnam.fhirhub.hl7;

import io.github.ramyagangapatnam.fhirhub.error.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates an ADT^A01 message against the structural rules from plan.md §3.1.
 *
 * <p>The check is intentionally hand-rolled rather than driven from HAPI HL7's full schema
 * validator: the spec pins a small, fixed allow-list of {@link ErrorCode} values per failure path,
 * and HAPI's generic structural messages do not round-trip cleanly to that allow-list. This
 * implementation reads the raw bytes once, splits on {@code \r} segment terminators, and emits
 * issues whose {@code location} is purely structural ({@code MSH-12}, {@code PID(missing)}) — never
 * a field value (Principle I).
 *
 * <p>Principle IX (Schema Validation at Boundaries).
 */
public final class Adt01SchemaValidator {

  private static final Set<String> SUPPORTED_VERSIONS = Set.of("2.5", "2.5.1");
  private static final String SUPPORTED_MESSAGE_TYPE = "ADT^A01";

  /**
   * Validates the raw HL7 bytes. Returns the structured result. Pure (no DB writes); the caller
   * persists {@code validation_error} rows from {@link ValidationResult#issues()}.
   */
  public ValidationResult validate(String rawHl7) {
    List<Issue> issues = new ArrayList<>();

    if (rawHl7 == null || rawHl7.length() < 4 || !rawHl7.startsWith("MSH")) {
      issues.add(new Issue(ErrorCode.HL7_PARSE_INVALID_FRAMING, "MSH"));
      return new ValidationResult(issues);
    }

    // MSH-1 (the field separator) is the literal character at index 3. HL7 v2 mandates '|'.
    if (rawHl7.charAt(3) != '|') {
      issues.add(new Issue(ErrorCode.HL7_PARSE_INVALID_FRAMING, "MSH-1"));
    }

    // HL7 v2 segments are terminated with \r (0x0D). LF-only bodies are non-conformant.
    if (!rawHl7.contains("\r")) {
      issues.add(new Issue(ErrorCode.HL7_PARSE_INVALID_FRAMING, "MSH"));
    }

    // If framing itself is broken, MSH-9 / MSH-12 / PID / PV1 lookups would be unreliable.
    if (!issues.isEmpty()) {
      return new ValidationResult(issues);
    }

    Map<String, String> firstByType = indexSegmentsByType(rawHl7);

    String msh = firstByType.get("MSH");
    if (msh == null) {
      // Defence-in-depth: we already verified rawHl7 begins with "MSH" above.
      issues.add(new Issue(ErrorCode.HL7_PARSE_MISSING_SEGMENT, "MSH(missing)"));
      return new ValidationResult(issues);
    }

    String[] mshFields = msh.split("\\|", -1);
    // mshFields[0] = "MSH", [1] = encoding chars, [2] = MSH-3, ..., [8] = MSH-9, [11] = MSH-12.
    String msh9 = mshFields.length > 8 ? mshFields[8] : "";
    String msh12 = mshFields.length > 11 ? mshFields[11] : "";

    if (!SUPPORTED_MESSAGE_TYPE.equals(extractMessageTypeCode(msh9))) {
      issues.add(new Issue(ErrorCode.HL7_PARSE_UNSUPPORTED_MESSAGE_TYPE, "MSH-9"));
    }

    if (!SUPPORTED_VERSIONS.contains(msh12)) {
      issues.add(new Issue(ErrorCode.HL7_PARSE_UNSUPPORTED_VERSION, "MSH-12"));
    }

    if (!firstByType.containsKey("PID")) {
      issues.add(new Issue(ErrorCode.HL7_PARSE_MISSING_SEGMENT, "PID(missing)"));
    }

    if (!firstByType.containsKey("PV1")) {
      issues.add(new Issue(ErrorCode.HL7_PARSE_MISSING_SEGMENT, "PV1(missing)"));
    }

    return new ValidationResult(issues);
  }

  private static Map<String, String> indexSegmentsByType(String rawHl7) {
    Map<String, String> firstByType = new LinkedHashMap<>();
    for (String segment : rawHl7.split("\r")) {
      if (segment.length() < 3) {
        continue;
      }
      String type = segment.substring(0, 3);
      firstByType.putIfAbsent(type, segment);
    }
    return firstByType;
  }

  /**
   * MSH-9 may be either {@code ADT^A01} or {@code ADT^A01^ADT_A01} (with the structure code as the
   * third component). We compare only on the {@code message-code ^ trigger-event} prefix.
   */
  private static String extractMessageTypeCode(String msh9) {
    if (msh9 == null || msh9.isEmpty()) {
      return "";
    }
    String[] parts = msh9.split("\\^", -1);
    if (parts.length < 2) {
      return parts[0];
    }
    return parts[0] + "^" + parts[1];
  }

  /** Outcome of a validation pass. */
  public record ValidationResult(List<Issue> issues) {
    public boolean isValid() {
      return issues == null || issues.isEmpty();
    }
  }

  /**
   * Single structured validation finding. {@code location} follows the {@code SEG} / {@code
   * SEG-idx} grammar from plan.md §3.7 — never a value.
   */
  public record Issue(ErrorCode code, String location) {}
}
