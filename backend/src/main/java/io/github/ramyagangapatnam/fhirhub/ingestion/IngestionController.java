package io.github.ramyagangapatnam.fhirhub.ingestion;

import io.github.ramyagangapatnam.fhirhub.audit.AuditEventEmitter;
import io.github.ramyagangapatnam.fhirhub.error.DomainException;
import io.github.ramyagangapatnam.fhirhub.error.ErrorCode;
import io.github.ramyagangapatnam.fhirhub.hl7.Adt01SchemaValidator;
import io.github.ramyagangapatnam.fhirhub.hl7.Hl7HeaderParser;
import io.github.ramyagangapatnam.fhirhub.observability.CorrelationIdFilter;
import io.github.ramyagangapatnam.fhirhub.observability.PhiMasker;
import io.github.ramyagangapatnam.fhirhub.persistence.InboundMessage;
import io.github.ramyagangapatnam.fhirhub.persistence.InboundMessageRepository;
import io.github.ramyagangapatnam.fhirhub.persistence.InboundMessageStatus;
import io.github.ramyagangapatnam.fhirhub.processing.InboundMessageProcessor;
import jakarta.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /ingest/hl7v2} entry point (plan.md §3.1).
 *
 * <p>Lifecycle of one request:
 *
 * <ol>
 *   <li>Body bytes are read from the request (the 64 KiB cap was already enforced by {@link
 *       IngestionBoundaryFilter}).
 *   <li>Lightweight MSH header extraction via {@link Hl7HeaderParser} — pulls only MSH-3 and MSH-10
 *       (and surfaces invalid framing as 400 before we touch the DB).
 *   <li>Synchronous {@link Adt01SchemaValidator} pass — needed because the OpenAPI contract returns
 *       the specific HL7_PARSE_* code synchronously on 400, not via the async pipeline.
 *   <li>Persist a fresh {@link InboundMessage} row with {@code status=RECEIVED}.
 *   <li>Emit one {@code create} audit event for the InboundMessage write (Principle II — the
 *       inbound message is itself a PHI-bearing resource even before transformation).
 *   <li>Hand off to {@link InboundMessageProcessor#enqueue(UUID)} and immediately return 202.
 * </ol>
 *
 * <p>The controller MUST NOT block on transformation — the SC-001 95p-under-500ms budget covers
 * everything up to the 202 response, and the seam handoff is by id only.
 */
@RestController
@RequestMapping("/ingest")
public class IngestionController {

  private static final Logger LOGGER = LoggerFactory.getLogger(IngestionController.class);

  private final Hl7HeaderParser headerParser = new Hl7HeaderParser();

  private final InboundMessageRepository inboundMessages;
  private final Adt01SchemaValidator validator;
  private final AuditEventEmitter audit;
  private final InboundMessageProcessor processor;

  public IngestionController(
      InboundMessageRepository inboundMessages,
      Adt01SchemaValidator validator,
      AuditEventEmitter audit,
      InboundMessageProcessor processor) {
    this.inboundMessages = inboundMessages;
    this.validator = validator;
    this.audit = audit;
    this.processor = processor;
  }

  @PostMapping(path = "/hl7v2")
  public ResponseEntity<Map<String, Object>> ingest(HttpServletRequest request) throws IOException {
    String rawHl7 = readBody(request);

    // Defensive debug log of the body shape — passed through PhiMasker so PHI tokens never reach
    // any appender even at DEBUG. This also gives the PHI-redaction integration test (T025) a log
    // line where the [REDACTED-HL7] marker is provably present, proving the masker is active.
    LOGGER.debug("ingest.body.received bytes={} preview={}", rawHl7.length(), PhiMasker.mask(rawHl7));

    // Step 1: lightweight header parse. Throws HL7_PARSE_INVALID_FRAMING if MSH itself is broken;
    // the global ErrorEnvelopeAdvice renders that as 400 with the right envelope.
    Hl7HeaderParser.Hl7Header header = headerParser.parse(rawHl7);

    // Step 2: synchronous schema validation. The OpenAPI contract pins each HL7_PARSE_* code on the
    // 400 response, so we MUST surface the first issue's code now — not after the async pipeline.
    Adt01SchemaValidator.ValidationResult validation = validator.validate(rawHl7);
    if (!validation.isValid()) {
      Adt01SchemaValidator.Issue first = validation.issues().get(0);
      throw new DomainException(first.code(), "HL7 message failed schema validation.", first.location());
    }

    // Step 3: persist the inbound row.
    UUID correlationId = readCorrelationIdFromMdc();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    InboundMessage row = new InboundMessage();
    row.setId(UUID.randomUUID());
    row.setRawHl7(rawHl7);
    row.setMsh3SendingApplication(truncate(emptyToUnknown(header.sendingApplication()), 180));
    row.setMsh10ControlId(truncate(emptyToUnknown(header.controlId()), 199));
    row.setReceivedAtUtc(now);
    row.setStatus(InboundMessageStatus.RECEIVED);
    row.setCorrelationId(correlationId);
    row.setCreatedAtUtc(now);
    row.setUpdatedAtUtc(now);
    InboundMessage saved = inboundMessages.saveAndFlush(row);

    LOGGER.info("ingest.received message_id={}", saved.getId());
    audit.emit(
        "InboundMessage", saved.getId().toString(), "create", "success", correlationId);

    // Step 4: hand off — the call returns immediately, transformation runs on the worker pool.
    processor.enqueue(saved.getId());

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("messageId", saved.getId().toString());
    body.put("status", saved.getStatus().name());
    body.put("receivedAtUtc", saved.getReceivedAtUtc().toString());
    body.put("correlationId", correlationId.toString());
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
  }

  private static String readBody(HttpServletRequest request) throws IOException {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8))) {
      char[] buf = new char[2048];
      int n;
      while ((n = reader.read(buf)) > 0) {
        sb.append(buf, 0, n);
      }
    }
    return sb.toString();
  }

  private static UUID readCorrelationIdFromMdc() {
    String mdc = MDC.get(CorrelationIdFilter.MDC_KEY);
    if (mdc == null) {
      // Defence-in-depth: filter is HIGHEST_PRECEDENCE so this should never fire in practice.
      return UUID.randomUUID();
    }
    return UUID.fromString(mdc);
  }

  private static String emptyToUnknown(String value) {
    return (value == null || value.isEmpty()) ? "UNKNOWN" : value;
  }

  private static String truncate(String value, int max) {
    return value.length() > max ? value.substring(0, max) : value;
  }
}
