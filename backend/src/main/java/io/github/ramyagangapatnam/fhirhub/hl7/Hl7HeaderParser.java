package io.github.ramyagangapatnam.fhirhub.hl7;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.util.Terser;
import ca.uhn.hl7v2.validation.impl.NoValidation;
import io.github.ramyagangapatnam.fhirhub.error.DomainException;
import io.github.ramyagangapatnam.fhirhub.error.ErrorCode;

/**
 * Lightweight HL7 v2 header extractor: pulls MSH-3 (sending application), MSH-9 (message type),
 * MSH-10 (control id), and MSH-12 (version) only. Designed to run inside the HTTP request thread
 * before persistence so the controller can populate {@code
 * inbound_message.msh3_sending_application} + {@code msh10_control_id} (NOT NULL columns) and emit
 * the correct idempotency key.
 *
 * <p>Backed by HAPI HL7 v2's {@link PipeParser} configured with {@link NoValidation} — the lenient
 * mode skips structural validation so we don't pay for it twice (the full schema check happens in
 * {@link Adt01SchemaValidator} during T030).
 *
 * <p>If the message body does not begin with {@code MSH} or HAPI cannot extract the header encoding
 * at all, this parser throws {@link DomainException} with {@link
 * ErrorCode#HL7_PARSE_INVALID_FRAMING}.
 *
 * <p>Principle IX (Schema Validation at Boundaries).
 */
public final class Hl7HeaderParser {

  private final PipeParser parser;

  public Hl7HeaderParser() {
    HapiContext ctx = new DefaultHapiContext();
    ctx.setValidationContext(new NoValidation());
    this.parser = ctx.getPipeParser();
  }

  /**
   * Extracts MSH-3, MSH-9, MSH-10, MSH-12 from {@code rawHl7}. The returned record carries empty
   * strings (not nulls) for any field HAPI cannot locate so the caller doesn't have to null-guard
   * each lookup — empties bubble through to the full validator which will flag them with the
   * matching {@code HL7_PARSE_*} code.
   */
  public Hl7Header parse(String rawHl7) {
    if (rawHl7 == null || rawHl7.length() < 3 || !rawHl7.startsWith("MSH")) {
      throw new DomainException(
          ErrorCode.HL7_PARSE_INVALID_FRAMING,
          "Message does not begin with an MSH segment.",
          "MSH");
    }
    try {
      Message msg = parser.parse(rawHl7);
      Terser t = new Terser(msg);
      String sendingApplication = nullSafe(t.get("/MSH-3-1"));
      String messageType =
          composeMessageType(nullSafe(t.get("/MSH-9-1")), nullSafe(t.get("/MSH-9-2")));
      String controlId = nullSafe(t.get("/MSH-10-1"));
      String version = nullSafe(t.get("/MSH-12-1"));
      return new Hl7Header(sendingApplication, messageType, controlId, version);
    } catch (HL7Exception ex) {
      throw new DomainException(
          ErrorCode.HL7_PARSE_INVALID_FRAMING,
          "Could not parse HL7 v2 message framing.",
          "MSH",
          ex);
    }
  }

  private static String composeMessageType(String code, String triggerEvent) {
    if (code.isEmpty() && triggerEvent.isEmpty()) {
      return "";
    }
    if (triggerEvent.isEmpty()) {
      return code;
    }
    return code + "^" + triggerEvent;
  }

  private static String nullSafe(String s) {
    return s == null ? "" : s;
  }

  /**
   * Header extract surface. Fields are empty strings (never null) when HAPI could not locate the
   * corresponding component — see {@link #parse(String)}.
   */
  public record Hl7Header(
      String sendingApplication, String messageType, String controlId, String version) {}
}
