package io.github.ramyagangapatnam.fhirhub.transform;

import io.github.ramyagangapatnam.fhirhub.audit.AuditEventEmitter;
import io.github.ramyagangapatnam.fhirhub.error.ErrorCode;
import io.github.ramyagangapatnam.fhirhub.fhir.FhirResourceRepository;
import io.github.ramyagangapatnam.fhirhub.hl7.Adt01SchemaValidator;
import io.github.ramyagangapatnam.fhirhub.idempotency.IdempotencyArbiter;
import io.github.ramyagangapatnam.fhirhub.persistence.FhirResourceType;
import io.github.ramyagangapatnam.fhirhub.persistence.InboundMessage;
import io.github.ramyagangapatnam.fhirhub.persistence.InboundMessageRepository;
import io.github.ramyagangapatnam.fhirhub.persistence.InboundMessageStatus;
import io.github.ramyagangapatnam.fhirhub.persistence.ValidationErrorRepository;
import io.github.ramyagangapatnam.fhirhub.persistence.ValidationErrorRow;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * The HL7-to-FHIR orchestration pipeline. Operates against a single {@code inbound_message} row by
 * id (per the seam invariant in plan.md §5: never the raw body across the seam).
 *
 * <p>Steps, in order:
 *
 * <ol>
 *   <li>Set status {@code RECEIVED → VALIDATING}.
 *   <li>Schema-validate against {@link Adt01SchemaValidator}. On invalid: persist {@link
 *       ValidationErrorRow}s, set status {@code FAILED}, emit a failure audit event for the inbound
 *       message, return.
 *   <li>Reserve the {@code (sending_application, msh10_control_id)} idempotency key via {@link
 *       IdempotencyArbiter}. If a prior reservation already exists (loser of a concurrent race),
 *       short-circuit to {@code PERSISTED} without creating new FHIR resources — Principle VIII
 *       primary enforcement.
 *   <li>If winner: transform PID → Patient and PV1 → Encounter, upsert each via {@link
 *       FhirResourceRepository}, bind the FHIR ids to the idempotency key, set status to {@code
 *       PERSISTED}.
 *   <li>Emit one audit event per resource write (Principle II).
 * </ol>
 *
 * <p>Errors raised by {@link FhirResourceRepository#upsert} — including a partial-unique-index
 * violation from V3 — are folded into {@link ErrorCode#PERSIST_IDEMPOTENCY_CONFLICT} on the inbound
 * message and surfaced as a failed audit event.
 */
@Service
@ConditionalOnBean(InboundMessageRepository.class)
public class MessageTransformationService {

  private static final Logger LOGGER = LoggerFactory.getLogger(MessageTransformationService.class);

  private final InboundMessageRepository inboundMessages;
  private final ValidationErrorRepository validationErrors;
  private final Adt01SchemaValidator validator;
  private final PidToPatientMapper patientMapper;
  private final Pv1ToEncounterMapper encounterMapper;
  private final FhirResourceRepository fhirResources;
  private final IdempotencyArbiter arbiter;
  private final AuditEventEmitter audit;

  public MessageTransformationService(
      InboundMessageRepository inboundMessages,
      ValidationErrorRepository validationErrors,
      Adt01SchemaValidator validator,
      PidToPatientMapper patientMapper,
      Pv1ToEncounterMapper encounterMapper,
      FhirResourceRepository fhirResources,
      IdempotencyArbiter arbiter,
      AuditEventEmitter audit) {
    this.inboundMessages = inboundMessages;
    this.validationErrors = validationErrors;
    this.validator = validator;
    this.patientMapper = patientMapper;
    this.encounterMapper = encounterMapper;
    this.fhirResources = fhirResources;
    this.arbiter = arbiter;
    this.audit = audit;
  }

  /**
   * Run the pipeline against a single inbound message id. Idempotent: re-invoking against the same
   * id after a successful run leaves the DB unchanged and writes a fresh audit event for the read
   * of the inbound message.
   */
  public Result process(UUID inboundMessageId) {
    InboundMessage message =
        inboundMessages
            .findById(inboundMessageId)
            .orElseThrow(
                () ->
                    new NoSuchElementException(
                        "inbound_message id not found: " + inboundMessageId));

    setStatus(message, InboundMessageStatus.VALIDATING);

    Adt01SchemaValidator.ValidationResult validation = validator.validate(message.getRawHl7());
    if (!validation.isValid()) {
      persistValidationErrors(message.getId(), validation.issues());
      Adt01SchemaValidator.Issue first = validation.issues().get(0);
      message.setLastErrorCode(first.code().name());
      message.setLastErrorLocation(first.location());
      setStatus(message, InboundMessageStatus.FAILED);
      audit.emit(
          "InboundMessage",
          message.getId().toString(),
          "create",
          "failure",
          message.getCorrelationId());
      return Result.failed(first.code(), first.location());
    }

    IdempotencyArbiter.Outcome reservation =
        arbiter.reserve(
            message.getMsh3SendingApplication(), message.getMsh10ControlId(), message.getId());

    if (reservation instanceof IdempotencyArbiter.Outcome.AlreadyReserved existing) {
      // Loser of the concurrent race. Principle VIII says this is an idempotent success, not a
      // failure — the winner is responsible for the FHIR resources, we just walk our own
      // inbound_message to PERSISTED.
      setStatus(message, InboundMessageStatus.PERSISTED);
      LOGGER.info(
          "ingest.dedupe.hit message_id={} idempotency_key={}",
          message.getId(),
          existing.idempotencyKeyId());
      return Result.deduped(
          existing.idempotencyKeyId(),
          existing.patientResourceId(),
          existing.encounterResourceId());
    }

    IdempotencyArbiter.Outcome.Won won = (IdempotencyArbiter.Outcome.Won) reservation;

    String patientId = UUID.randomUUID().toString();
    String encounterId = UUID.randomUUID().toString();
    try {
      Patient patient = patientMapper.toPatient(message.getRawHl7(), patientId);
      Encounter encounter =
          encounterMapper.toEncounter(message.getRawHl7(), encounterId, patientId);

      setStatus(message, InboundMessageStatus.TRANSFORMED);

      FhirResourceRepository.Stored storedPatient =
          fhirResources.upsert(FhirResourceType.Patient, patient);
      audit.emit("Patient", storedPatient.id(), "create", "success", message.getCorrelationId());

      FhirResourceRepository.Stored storedEncounter =
          fhirResources.upsert(FhirResourceType.Encounter, encounter);
      audit.emit(
          "Encounter", storedEncounter.id(), "create", "success", message.getCorrelationId());

      arbiter.bindResources(won.idempotencyKeyId(), storedPatient.id(), storedEncounter.id());
      setStatus(message, InboundMessageStatus.PERSISTED);
      return Result.persisted(won.idempotencyKeyId(), storedPatient.id(), storedEncounter.id());
    } catch (DataIntegrityViolationException ex) {
      // Race on the partial-unique business identifier index (V3 secondary enforcement). Treat as
      // an idempotency conflict per error-codes.md.
      message.setLastErrorCode(ErrorCode.PERSIST_IDEMPOTENCY_CONFLICT.name());
      message.setLastErrorLocation(null);
      setStatus(message, InboundMessageStatus.FAILED);
      audit.emit(
          "InboundMessage",
          message.getId().toString(),
          "create",
          "failure",
          message.getCorrelationId());
      return Result.failed(ErrorCode.PERSIST_IDEMPOTENCY_CONFLICT, null);
    }
  }

  private void setStatus(InboundMessage message, InboundMessageStatus newStatus) {
    message.setStatus(newStatus);
    inboundMessages.saveAndFlush(message);
  }

  private void persistValidationErrors(
      UUID inboundMessageId, List<Adt01SchemaValidator.Issue> issues) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    for (Adt01SchemaValidator.Issue issue : issues) {
      ValidationErrorRow row = new ValidationErrorRow();
      row.setId(UUID.randomUUID());
      row.setInboundMessageId(inboundMessageId);
      row.setErrorCode(issue.code().name());
      row.setSegmentField(issue.location());
      row.setSummarySafe(safeSummary(issue.code()));
      row.setCreatedAtUtc(now);
      validationErrors.save(row);
    }
  }

  /**
   * Display-safe summary derived from the {@link ErrorCode} alone. Per Principle I, this MUST NOT
   * incorporate the source-message value — only the code's structural meaning.
   */
  private static String safeSummary(ErrorCode code) {
    return switch (code) {
      case HL7_PARSE_INVALID_FRAMING ->
          "HL7 framing was invalid (encoding chars, segment terminator, or field separator).";
      case HL7_PARSE_MISSING_SEGMENT -> "A required HL7 segment was not present.";
      case HL7_PARSE_MISSING_REQUIRED_FIELD -> "A required HL7 field was not present.";
      case HL7_PARSE_UNSUPPORTED_VERSION -> "MSH-12 declares an unsupported HL7 version.";
      case HL7_PARSE_UNSUPPORTED_MESSAGE_TYPE -> "MSH-9 declares an unsupported message type.";
      case HL7_PARSE_ENCODING_ERROR -> "HL7 body contained bytes outside the declared encoding.";
      case FHIR_TRANSFORM_UNMAPPABLE_FIELD ->
          "A required FHIR target field has no source data and no default mapping.";
      case FHIR_TRANSFORM_INTERNAL_ERROR -> "Transformation failed for an internal reason.";
      case PERSIST_IDEMPOTENCY_CONFLICT ->
          "Idempotency conflict at the FHIR persistence layer; resource already exists.";
      default -> "Validation or transformation failure.";
    };
  }

  /** Terminal outcome of the pipeline. Surfaced for tests and for the Inspector replay path. */
  public sealed interface Result {

    static Result persisted(UUID idempotencyKeyId, String patientId, String encounterId) {
      return new Persisted(idempotencyKeyId, patientId, encounterId);
    }

    static Result deduped(UUID idempotencyKeyId, String patientId, String encounterId) {
      return new Deduped(idempotencyKeyId, patientId, encounterId);
    }

    static Result failed(ErrorCode code, String location) {
      return new Failed(code, location);
    }

    /** Winner of the idempotency race — FHIR rows were created or version-bumped. */
    record Persisted(UUID idempotencyKeyId, String patientResourceId, String encounterResourceId)
        implements Result {}

    /** Loser of the idempotency race — no FHIR rows were touched; existing ids returned. */
    record Deduped(UUID idempotencyKeyId, String patientResourceId, String encounterResourceId)
        implements Result {}

    /** Validation or transformation failure; {@code inbound_message.status = FAILED}. */
    record Failed(ErrorCode code, String location) implements Result {}
  }
}
