package io.github.ramyagangapatnam.fhirhub.observability;

import java.util.regex.Pattern;

/**
 * Pure-function PHI masking. Used by {@link MaskingConverter} (Logback) and by
 * {@link PhiAttributeSanitizer} (OpenTelemetry) so the same redaction rules apply to logs and to
 * span attributes. Principle I (NON-NEGOTIABLE).
 *
 * <p>Rules:
 *
 * <ul>
 *   <li>Any HL7 v2 segment marker (three uppercase letters followed by {@code |}) and the rest of
 *       its line are replaced with {@code [REDACTED-HL7]}. This covers raw_hl7 echoes regardless of
 *       context — even partial segments leaked into a log line are caught.
 *   <li>The HL7 encoding characters field {@code |^~\&} is treated as a strong HL7 indicator and
 *       triggers full-line redaction.
 *   <li>Eight consecutive digits (HL7 DOB / TS / ID patterns) are replaced with {@code [REDACTED-8D]}.
 *   <li>SSN-like patterns ({@code NNN-NN-NNNN}) are replaced with {@code [REDACTED-SSN]}.
 *   <li>Phone-like patterns ({@code (NNN)NNN-NNNN} / {@code NNN-NNN-NNNN}) are replaced with
 *       {@code [REDACTED-PHONE]}.
 *   <li>Email addresses are replaced with {@code [REDACTED-EMAIL]}.
 * </ul>
 *
 * <p>Order matters: HL7 segment-line redaction runs first so it absorbs whatever PHI lives inside
 * the segment, including birth dates and IDs that the narrower patterns would otherwise catch.
 */
public final class PhiMasker {

  private static final Pattern HL7_SEGMENT_LINE =
      Pattern.compile("\\b[A-Z]{3}\\|[^\\r\\n]*", Pattern.MULTILINE);

  // Catches the HL7 encoding characters field even if a snippet starts mid-message.
  private static final Pattern HL7_ENCODING_CHARS = Pattern.compile("\\|\\^~\\\\&[^\\r\\n]*");

  private static final Pattern SSN_PATTERN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");

  private static final Pattern PHONE_PATTERN =
      Pattern.compile("\\b(?:\\(\\d{3}\\)\\s?|\\d{3}[-.])\\d{3}[-.]\\d{4}\\b");

  private static final Pattern EMAIL_PATTERN =
      Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

  private static final Pattern EIGHT_DIGITS = Pattern.compile("\\b\\d{8}\\b");

  private PhiMasker() {}

  /** Returns {@code input} with PHI replaced. Null-safe. */
  public static String mask(String input) {
    if (input == null || input.isEmpty()) {
      return input;
    }
    String s = input;
    s = HL7_SEGMENT_LINE.matcher(s).replaceAll("[REDACTED-HL7]");
    s = HL7_ENCODING_CHARS.matcher(s).replaceAll("[REDACTED-HL7]");
    s = EMAIL_PATTERN.matcher(s).replaceAll("[REDACTED-EMAIL]");
    s = SSN_PATTERN.matcher(s).replaceAll("[REDACTED-SSN]");
    s = PHONE_PATTERN.matcher(s).replaceAll("[REDACTED-PHONE]");
    s = EIGHT_DIGITS.matcher(s).replaceAll("[REDACTED-8D]");
    return s;
  }
}
