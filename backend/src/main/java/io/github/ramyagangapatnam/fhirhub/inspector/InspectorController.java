package io.github.ramyagangapatnam.fhirhub.inspector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ramyagangapatnam.fhirhub.audit.AuditEventEmitter;
import io.github.ramyagangapatnam.fhirhub.error.DomainException;
import io.github.ramyagangapatnam.fhirhub.error.ErrorCode;
import io.github.ramyagangapatnam.fhirhub.fhir.FhirResourceRepository;
import io.github.ramyagangapatnam.fhirhub.persistence.FhirResourceType;
import io.github.ramyagangapatnam.fhirhub.persistence.IdempotencyKey;
import io.github.ramyagangapatnam.fhirhub.persistence.IdempotencyKeyRepository;
import io.github.ramyagangapatnam.fhirhub.persistence.InboundMessage;
import io.github.ramyagangapatnam.fhirhub.persistence.InboundMessageRepository;
import io.github.ramyagangapatnam.fhirhub.persistence.ValidationErrorRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Backend for the Angular Inspector SPA (plan.md §3.4–§3.6). Lists ingested messages, returns one
 * message's raw HL7 + derived FHIR side-by-side, and replays a message against its persisted raw
 * body.
 *
 * <p>The list view (T046) is metadata-only — it NEVER returns the raw HL7 body, so the default
 * operator view exposes no PHI. The detail and replay endpoints (T047, T048) return the raw body
 * and emit audit events because that access is authenticated and audited (Principle II).
 */
@RestController
@RequestMapping("/inspector")
public class InspectorController {

  static final int DEFAULT_LIMIT = 50;
  static final int MAX_LIMIT = 200;

  private final JdbcTemplate jdbc;
  private final InboundMessageRepository inboundMessages;
  private final ValidationErrorRepository validationErrors;
  private final IdempotencyKeyRepository idempotencyKeys;
  private final FhirResourceRepository fhirResources;
  private final AuditEventEmitter audit;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public InspectorController(
      DataSource dataSource,
      InboundMessageRepository inboundMessages,
      ValidationErrorRepository validationErrors,
      IdempotencyKeyRepository idempotencyKeys,
      FhirResourceRepository fhirResources,
      AuditEventEmitter audit) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.inboundMessages = inboundMessages;
    this.validationErrors = validationErrors;
    this.idempotencyKeys = idempotencyKeys;
    this.fhirResources = fhirResources;
    this.audit = audit;
  }

  /**
   * {@code GET /inspector/messages}: status filter (single or repeated), {@code msh10} exact-match
   * search, {@code received_at_utc} descending order, and {@code limit}/{@code offset} pagination
   * (limit clamped to [1, 200], default 50). The raw HL7 body is intentionally excluded from the
   * projection.
   */
  @GetMapping("/messages")
  public MessageListPage list(
      @RequestParam(name = "status", required = false) List<String> status,
      @RequestParam(name = "msh10", required = false) String msh10,
      @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit,
      @RequestParam(name = "offset", defaultValue = "0") int offset) {

    int effectiveLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
    int effectiveOffset = Math.max(offset, 0);

    StringBuilder where = new StringBuilder();
    List<Object> filterArgs = new ArrayList<>();
    if (status != null && !status.isEmpty()) {
      where.append(where.isEmpty() ? " WHERE " : " AND ").append("status IN (");
      for (int i = 0; i < status.size(); i++) {
        where.append(i == 0 ? "?" : ",?");
        filterArgs.add(status.get(i));
      }
      where.append(")");
    }
    if (msh10 != null && !msh10.isBlank()) {
      where.append(where.isEmpty() ? " WHERE " : " AND ").append("msh10_control_id = ?");
      filterArgs.add(msh10);
    }

    Long total =
        jdbc.queryForObject(
            "SELECT count(*) FROM inbound_message" + where, Long.class, filterArgs.toArray());

    List<Object> pageArgs = new ArrayList<>(filterArgs);
    pageArgs.add(effectiveLimit);
    pageArgs.add(effectiveOffset);
    List<MessageListItem> messages =
        jdbc.query(
            "SELECT id, msh10_control_id, msh3_sending_application, received_at_utc, status,"
                + " last_error_code FROM inbound_message"
                + where
                + " ORDER BY received_at_utc DESC LIMIT ? OFFSET ?",
            (rs, rowNum) ->
                new MessageListItem(
                    rs.getObject("id", UUID.class).toString(),
                    rs.getString("msh10_control_id"),
                    rs.getString("msh3_sending_application"),
                    rs.getObject("received_at_utc", OffsetDateTime.class).toString(),
                    rs.getString("status"),
                    rs.getString("last_error_code")),
            pageArgs.toArray());

    return new MessageListPage(
        messages, new Page(effectiveLimit, effectiveOffset, total == null ? 0L : total));
  }

  /**
   * {@code GET /inspector/messages/{messageId}}: raw HL7 + derived FHIR Patient/Encounter +
   * validation errors. Emits a {@code read} audit event for the inbound message and for each FHIR
   * resource returned (Principle II). {@code fhirResources} is {@code null} when the message never
   * produced resources (e.g. FAILED validation).
   */
  @GetMapping("/messages/{messageId}")
  public MessageDetail detail(@PathVariable UUID messageId) {
    InboundMessage message = requireMessage(messageId);
    UUID correlationId = message.getCorrelationId();

    audit.emit("InboundMessage", message.getId().toString(), "read", "success", correlationId);

    List<ValidationErrorView> errors =
        validationErrors.findByInboundMessageId(messageId).stream()
            .map(
                row ->
                    new ValidationErrorView(
                        row.getErrorCode(), row.getSegmentField(), row.getSummarySafe()))
            .toList();

    FhirResources bundle = loadFhirResources(message, correlationId);

    return new MessageDetail(
        message.getId().toString(),
        message.getMsh10ControlId(),
        message.getMsh3SendingApplication(),
        message.getReceivedAtUtc().toString(),
        message.getStatus().name(),
        message.getRawHl7(),
        errors,
        bundle,
        correlationId.toString());
  }

  /**
   * Resolve the derived FHIR resources for a message via its idempotency key (which carries the
   * Patient + Encounter logical ids). Returns {@code null} when no resources have been produced.
   * Emits a {@code read} audit event per resource actually returned.
   */
  private FhirResources loadFhirResources(InboundMessage message, UUID correlationId) {
    Optional<IdempotencyKey> key =
        idempotencyKeys.findBySendingApplicationAndMsh10ControlId(
            message.getMsh3SendingApplication(), message.getMsh10ControlId());
    if (key.isEmpty()) {
      return null;
    }
    String patientId = key.get().getPatientResourceId();
    String encounterId = key.get().getEncounterResourceId();
    if (patientId == null || encounterId == null) {
      return null;
    }

    Optional<FhirResourceRepository.Stored> patient =
        fhirResources.findById(FhirResourceType.Patient, patientId);
    Optional<FhirResourceRepository.Stored> encounter =
        fhirResources.findById(FhirResourceType.Encounter, encounterId);
    if (patient.isEmpty() || encounter.isEmpty()) {
      return null;
    }

    audit.emit("Patient", patientId, "read", "success", correlationId);
    audit.emit("Encounter", encounterId, "read", "success", correlationId);
    return new FhirResources(
        parseJson(patient.get().contentJson()), parseJson(encounter.get().contentJson()));
  }

  private InboundMessage requireMessage(UUID messageId) {
    return inboundMessages
        .findById(messageId)
        .orElseThrow(
            () ->
                new DomainException(
                    ErrorCode.INSPECTOR_MESSAGE_NOT_FOUND,
                    "No message exists for the supplied id.",
                    null));
  }

  /**
   * Parse the stored {@code content_json} into a plain object graph (Map/List) so Jackson
   * re-serializes it as a nested JSON object in the detail response. We deliberately avoid
   * embedding a {@code JsonNode} directly: on this classpath the response serializer renders a raw
   * {@code JsonNode} as its bean properties ({@code nodeType}, {@code array}, …) rather than as the
   * tree, so a Map/List graph is the reliable representation.
   */
  private Object parseJson(String json) {
    try {
      return objectMapper.readValue(json, Object.class);
    } catch (JsonProcessingException ex) {
      // content_json was written by HAPI's IParser and is always valid JSON; unreachable in
      // practice.
      throw new DomainException(
          ErrorCode.FHIR_TRANSFORM_INTERNAL_ERROR,
          "Stored FHIR resource was not valid JSON.",
          null);
    }
  }

  /** One row in the list view — metadata only, no raw HL7. */
  public record MessageListItem(
      String messageId,
      String msh10ControlId,
      String sendingApplication,
      String receivedAtUtc,
      String status,
      String lastErrorCode) {}

  /** Pagination envelope. */
  public record Page(int limit, int offset, long total) {}

  /** A page of list items. */
  public record MessageListPage(List<MessageListItem> messages, Page page) {}

  /** A structured, display-safe validation/transformation finding. */
  public record ValidationErrorView(String errorCode, String segmentField, String summarySafe) {}

  /** The derived FHIR resources, serialized as nested JSON objects. */
  public record FhirResources(Object patient, Object encounter) {}

  /** Detail view: raw HL7 + FHIR side-by-side + validation errors. */
  public record MessageDetail(
      String messageId,
      String msh10ControlId,
      String sendingApplication,
      String receivedAtUtc,
      String status,
      String rawHl7,
      List<ValidationErrorView> validationErrors,
      FhirResources fhirResources,
      String correlationId) {}
}
