package io.github.ramyagangapatnam.fhirhub.processing;

import java.util.UUID;

/**
 * The seam between the synchronous ingestion handshake and the downstream HL7-to-FHIR pipeline (see
 * plan.md §5).
 *
 * <p>Implementations MUST:
 *
 * <ul>
 *   <li>NOT block waiting for transformation to complete — the calling HTTP thread must return 202
 *       within the SC-001 budget.
 *   <li>Carry ONLY the inbound message identifier across the seam, never the raw HL7 body. This is
 *       what lets us swap the in-process implementation for an SQS-backed one later without putting
 *       PHI through a queue.
 *   <li>Preserve the correlation ID (and any other MDC keys established by {@code
 *       CorrelationIdFilter}) so log lines and spans emitted by the worker still join the
 *       originating HTTP request's trace.
 * </ul>
 *
 * <p>Principle VII (Observability from Day One).
 */
public interface InboundMessageProcessor {

  /**
   * Hand off transformation responsibility for the inbound message whose row has already been
   * persisted in {@code inbound_message}. Returns immediately.
   */
  void enqueue(UUID inboundMessageId);
}
