package io.github.ramyagangapatnam.fhirhub.config;

import io.github.ramyagangapatnam.fhirhub.error.DomainException;
import io.github.ramyagangapatnam.fhirhub.error.ErrorCode;
import io.github.ramyagangapatnam.fhirhub.observability.CorrelationIdFilter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates exceptions raised inside controllers into the stable error envelope defined in
 * plan.md §3.7. Stack traces NEVER appear in responses; {@code correlationId} is always populated
 * from MDC (null if no filter set it, which would only happen for a misconfigured environment).
 *
 * <p>Filters that reject before the dispatcher runs (auth, ingestion boundary) write the same
 * envelope shape directly — see {@code AuthFilter} and {@code IngestionBoundaryFilter}. This
 * advice handles the in-dispatcher path.
 *
 * <p>Principle IX (Schema Validation at Boundaries).
 */
@RestControllerAdvice
public class ErrorEnvelopeAdvice {

  private static final Logger log = LoggerFactory.getLogger(ErrorEnvelopeAdvice.class);

  @ExceptionHandler(DomainException.class)
  public ResponseEntity<Map<String, Object>> handleDomain(DomainException ex) {
    return envelope(ex.code(), ex.getMessage(), ex.safeLocation());
  }

  @ExceptionHandler(Throwable.class)
  public ResponseEntity<Map<String, Object>> handleUnexpected(Throwable ex) {
    log.error("Unhandled exception", ex);
    return envelope(ErrorCode.HTTP_INTERNAL_ERROR, "An internal error occurred.", null);
  }

  private static ResponseEntity<Map<String, Object>> envelope(
      ErrorCode code, String message, String location) {
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("code", code.name());
    error.put("message", message);
    if (location != null) {
      error.put("location", location);
    }
    error.put("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", error);
    return ResponseEntity.status(code.httpStatus()).body(body);
  }
}
