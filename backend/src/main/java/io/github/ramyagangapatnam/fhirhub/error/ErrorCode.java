package io.github.ramyagangapatnam.fhirhub.error;

import org.springframework.http.HttpStatus;

/**
 * Enumeration mirroring {@code specs/001-adt-a01-ingestion-inspection/contracts/error-codes.md}.
 *
 * <p>Each member carries the HTTP status it must surface as. Adding or renaming a code requires
 * a synchronous edit of the contract document — the enum and the doc are the same allow-list.
 *
 * <p>Principle IX (Schema Validation at Boundaries).
 */
public enum ErrorCode {
  // HTTP boundary
  HTTP_AUTH_MISSING_TOKEN(HttpStatus.UNAUTHORIZED),
  HTTP_AUTH_INVALID_TOKEN(HttpStatus.UNAUTHORIZED),
  HTTP_UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
  HTTP_PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),
  HTTP_NOT_FOUND(HttpStatus.NOT_FOUND),
  HTTP_INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),

  // HL7 parse and validation
  HL7_PARSE_INVALID_FRAMING(HttpStatus.BAD_REQUEST),
  HL7_PARSE_MISSING_SEGMENT(HttpStatus.BAD_REQUEST),
  HL7_PARSE_MISSING_REQUIRED_FIELD(HttpStatus.BAD_REQUEST),
  HL7_PARSE_UNSUPPORTED_VERSION(HttpStatus.BAD_REQUEST),
  HL7_PARSE_UNSUPPORTED_MESSAGE_TYPE(HttpStatus.BAD_REQUEST),
  HL7_PARSE_ENCODING_ERROR(HttpStatus.BAD_REQUEST),

  // Transformation
  FHIR_TRANSFORM_UNMAPPABLE_FIELD(HttpStatus.UNPROCESSABLE_ENTITY),
  FHIR_TRANSFORM_INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),

  // Persistence and idempotency
  PERSIST_IDEMPOTENCY_CONFLICT(HttpStatus.INTERNAL_SERVER_ERROR),
  PERSIST_DB_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
  PERSIST_S3_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),

  // Inspector and replay
  INSPECTOR_MESSAGE_NOT_FOUND(HttpStatus.NOT_FOUND),
  REPLAY_REVALIDATION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY);

  private final HttpStatus httpStatus;

  ErrorCode(HttpStatus httpStatus) {
    this.httpStatus = httpStatus;
  }

  public HttpStatus httpStatus() {
    return httpStatus;
  }
}
