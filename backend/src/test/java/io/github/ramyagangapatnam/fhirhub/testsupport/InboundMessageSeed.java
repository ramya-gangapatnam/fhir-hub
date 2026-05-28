package io.github.ramyagangapatnam.fhirhub.testsupport;

import io.github.ramyagangapatnam.fhirhub.persistence.InboundMessage;
import io.github.ramyagangapatnam.fhirhub.persistence.InboundMessageStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Builds {@link InboundMessage} rows for the Inspector tests without repeating the NOT-NULL
 * timestamp boilerplate. The ingest endpoint rejects invalid HL7 synchronously (400) and never
 * persists it, so the Inspector tests seed FAILED / pre-existing rows directly through the
 * repository rather than through {@code POST /ingest/hl7v2}.
 */
public final class InboundMessageSeed {

  private InboundMessageSeed() {}

  /** A row with explicit status, control id, sending application, and received-at instant. */
  public static InboundMessage row(
      String rawHl7,
      String sendingApplication,
      String msh10ControlId,
      InboundMessageStatus status,
      OffsetDateTime receivedAtUtc) {
    InboundMessage row = new InboundMessage();
    row.setId(UUID.randomUUID());
    row.setRawHl7(rawHl7);
    row.setMsh3SendingApplication(sendingApplication);
    row.setMsh10ControlId(msh10ControlId);
    row.setReceivedAtUtc(receivedAtUtc);
    row.setStatus(status);
    row.setCorrelationId(UUID.randomUUID());
    row.setCreatedAtUtc(receivedAtUtc);
    row.setUpdatedAtUtc(receivedAtUtc);
    return row;
  }
}
