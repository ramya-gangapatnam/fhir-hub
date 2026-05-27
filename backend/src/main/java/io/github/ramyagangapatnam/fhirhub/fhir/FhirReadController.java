package io.github.ramyagangapatnam.fhirhub.fhir;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ramyagangapatnam.fhirhub.audit.AuditEventEmitter;
import io.github.ramyagangapatnam.fhirhub.observability.CorrelationIdFilter;
import io.github.ramyagangapatnam.fhirhub.persistence.FhirResourceType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Custom FHIR R4 read endpoints for Patient and Encounter (plan.md §3.2–§3.3). Streams the
 * persisted {@code content_json} straight back to the wire — no HAPI re-parse, no ORM-to-FHIR
 * indirection — and emits one {@code read} audit event per access (Principle II).
 *
 * <p>Conformance details:
 *
 * <ul>
 *   <li>{@code Accept: application/fhir+json} (or {@code application/json}, {@code *}/{@code *},
 *       or omitted) → 200 with the FHIR resource bytes; weak {@code ETag} = {@code
 *       W/"<version_id>"}; {@code Last-Modified} from {@code last_updated_utc}.
 *   <li>Anything else (notably the explicitly out-of-scope {@code application/fhir+xml}) → 406
 *       wrapped in a FHIR {@code OperationOutcome}.
 *   <li>Unknown logical id → 404 wrapped in a FHIR {@code OperationOutcome} (per fhir-read.openapi
 *       §404 — clients polling between 202 and the 2s availability SLA see this routinely).
 * </ul>
 *
 * <p>The controller writes directly to {@link HttpServletResponse} so Spring's content negotiation
 * machinery cannot pre-empt the handler with a generic 406 — we need an {@code OperationOutcome}
 * body even on 406, which Spring's default error handling would not produce.
 */
@RestController
@RequestMapping("/fhir")
public class FhirReadController {

  static final String FHIR_JSON_MEDIA_TYPE = "application/fhir+json";
  static final String PLAIN_JSON_MEDIA_TYPE = "application/json";

  private final FhirResourceRepository repository;
  private final AuditEventEmitter audit;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public FhirReadController(FhirResourceRepository repository, AuditEventEmitter audit) {
    this.repository = repository;
    this.audit = audit;
  }

  @GetMapping("/Patient/{id}")
  public void readPatient(
      @PathVariable String id,
      @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept,
      HttpServletResponse response)
      throws IOException {
    read(FhirResourceType.Patient, id, accept, response);
  }

  @GetMapping("/Encounter/{id}")
  public void readEncounter(
      @PathVariable String id,
      @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept,
      HttpServletResponse response)
      throws IOException {
    read(FhirResourceType.Encounter, id, accept, response);
  }

  private void read(
      FhirResourceType resourceType, String id, String accept, HttpServletResponse response)
      throws IOException {
    UUID correlationId = readCorrelationIdFromMdc();

    if (!acceptIsSupported(accept)) {
      audit.emit(resourceType.name(), id, "read", "failure", correlationId);
      writeOperationOutcome(
          response,
          HttpStatus.NOT_ACCEPTABLE,
          "structure",
          "Unsupported Accept; only application/fhir+json is supported for this demo.");
      return;
    }

    Optional<FhirResourceRepository.Stored> found = repository.findById(resourceType, id);
    if (found.isEmpty()) {
      audit.emit(resourceType.name(), id, "read", "failure", correlationId);
      writeOperationOutcome(
          response,
          HttpStatus.NOT_FOUND,
          "not-found",
          "Resource of type " + resourceType.name() + " with id " + id + " was not found.");
      return;
    }

    FhirResourceRepository.Stored stored = found.get();
    audit.emit(resourceType.name(), stored.id(), "read", "success", correlationId);

    response.setStatus(HttpStatus.OK.value());
    response.setContentType(FHIR_JSON_MEDIA_TYPE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader(HttpHeaders.ETAG, stored.etag());
    response.setHeader(
        HttpHeaders.LAST_MODIFIED,
        DateTimeFormatter.RFC_1123_DATE_TIME
            .withLocale(Locale.US)
            .format(stored.lastUpdatedUtc().atZoneSameInstant(ZoneOffset.UTC)));
    response.getWriter().write(stored.contentJson());
  }

  /**
   * The OpenAPI lists {@code application/fhir+json} as the canonical type and {@code
   * application/json} as an accepted alias. A wildcard Accept (curl's default) and an absent
   * header both mean "give me what you have," which for this endpoint is FHIR JSON.
   */
  private static boolean acceptIsSupported(String accept) {
    if (accept == null || accept.isBlank()) {
      return true;
    }
    for (String entry : accept.split(",")) {
      String base = entry;
      int semi = base.indexOf(';');
      if (semi >= 0) {
        base = base.substring(0, semi);
      }
      base = base.trim().toLowerCase(Locale.ROOT);
      if (FHIR_JSON_MEDIA_TYPE.equals(base)
          || PLAIN_JSON_MEDIA_TYPE.equals(base)
          || "*/*".equals(base)
          || "application/*".equals(base)) {
        return true;
      }
    }
    return false;
  }

  private void writeOperationOutcome(
      HttpServletResponse response, HttpStatus status, String issueCode, String diagnostics)
      throws IOException {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("text", diagnostics);

    Map<String, Object> issue = new LinkedHashMap<>();
    issue.put("severity", "error");
    issue.put("code", issueCode);
    issue.put("details", details);
    issue.put("diagnostics", diagnostics);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("resourceType", "OperationOutcome");
    body.put("issue", List.of(issue));

    response.setStatus(status.value());
    response.setContentType(FHIR_JSON_MEDIA_TYPE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    try {
      response.getWriter().write(objectMapper.writeValueAsString(body));
    } catch (JsonProcessingException ex) {
      // The map is well-formed; this branch is unreachable in practice but keeps the compiler happy.
      throw new IOException("Failed to serialize OperationOutcome", ex);
    }
  }

  private static UUID readCorrelationIdFromMdc() {
    String mdc = MDC.get(CorrelationIdFilter.MDC_KEY);
    if (mdc == null) {
      return UUID.randomUUID();
    }
    return UUID.fromString(mdc);
  }
}
