package io.github.ramyagangapatnam.fhirhub.hl7;

import io.github.ramyagangapatnam.fhirhub.error.ErrorCode;
import java.util.List;

/**
 * Validates an ADT^A01 message against the structural rules from plan.md §3.1.
 *
 * <p><strong>Compilation stub — real implementation lands in T030.</strong> Method bodies throw
 * {@link UnsupportedOperationException} so the T021 unit test fails honestly against missing code.
 * Principle IV (Test-First for Business Logic).
 */
public final class Adt01SchemaValidator {

  /**
   * Validates the raw HL7 bytes. Returns the structured result. Pure (no DB writes); the caller
   * persists {@code validation_error} rows from {@link ValidationResult#issues()}.
   */
  public ValidationResult validate(String rawHl7) {
    throw new UnsupportedOperationException("Adt01SchemaValidator pending T030");
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
