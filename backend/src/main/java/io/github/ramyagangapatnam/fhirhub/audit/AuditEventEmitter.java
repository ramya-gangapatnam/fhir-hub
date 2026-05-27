package io.github.ramyagangapatnam.fhirhub.audit;

import java.util.UUID;

/**
 * Principle II (PHI Access Auditing — NON-NEGOTIABLE) contract. Every code path that creates,
 * reads, updates, or replays a PHI-bearing resource MUST call this interface. T039 will replace the
 * in-process no-op with the file/S3 JSONL sinks; until then T035 still calls this from every
 * resource write so the orchestration is wired correctly and only the sink swaps.
 *
 * <p>Implementations MUST enforce the schema in {@code contracts/audit-event.schema.json}: {@code
 * auditId}, {@code schemaVersion}, {@code timestampUtc}, and {@code correlationId} (from MDC) are
 * filled by the emitter, not by the caller. The caller supplies only the domain-meaningful fields
 * below.
 */
public interface AuditEventEmitter {

  /**
   * Emit an audit record describing an operation against a hub-internal resource.
   *
   * @param resourceType {@code InboundMessage}, {@code Patient}, {@code Encounter}, or {@code
   *     AuditEvent}
   * @param resourceId hub-assigned identifier (NEVER a FHIR business identifier)
   * @param operation {@code create}, {@code read}, {@code update}, {@code replay}, {@code delete}
   * @param outcome {@code success} or {@code failure}
   * @param correlationId end-to-end correlation id for SC-009; falls back to MDC if {@code null}
   */
  void emit(
      String resourceType, String resourceId, String operation, String outcome, UUID correlationId);
}
