package io.github.ramyagangapatnam.fhirhub.error;

/**
 * Exception type carrying an {@link ErrorCode} plus a summary-safe message and an optional
 * structural location ({@code SEG-idx}). Throw this from controllers and services when a known
 * boundary condition has been hit; {@code ErrorEnvelopeAdvice} converts it into the error envelope
 * with the matching HTTP status.
 *
 * <p>The {@code message} and {@code location} fields MUST NOT carry any value from the source HL7
 * message — per Principle I + plan.md §3.7, only structural references are allowed.
 */
public class DomainException extends RuntimeException {

  private final ErrorCode code;
  private final String safeLocation;

  public DomainException(ErrorCode code, String safeMessage) {
    this(code, safeMessage, null, null);
  }

  public DomainException(ErrorCode code, String safeMessage, String safeLocation) {
    this(code, safeMessage, safeLocation, null);
  }

  public DomainException(ErrorCode code, String safeMessage, String safeLocation, Throwable cause) {
    super(safeMessage, cause);
    this.code = code;
    this.safeLocation = safeLocation;
  }

  public ErrorCode code() {
    return code;
  }

  public String safeLocation() {
    return safeLocation;
  }
}
