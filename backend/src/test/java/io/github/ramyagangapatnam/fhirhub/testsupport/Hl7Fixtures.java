package io.github.ramyagangapatnam.fhirhub.testsupport;

/**
 * Hand-authored, synthetic-only HL7 v2 ADT^A01 fixtures used by the User Story 1 test suite.
 *
 * <p>Every field value here is invented; no real person is described. The fixtures cover the four
 * canonical paths called out in plan.md §3 and the spec's "Independent Test" narrative for US1:
 *
 * <ul>
 *   <li>{@link #GOOD} — schema-valid ADT^A01 (v2.5)
 *   <li>{@link #MISSING_PID} — required PID segment absent → {@code HL7_PARSE_MISSING_SEGMENT}
 *   <li>{@link #BAD_FRAMING} — broken segment terminator → {@code HL7_PARSE_INVALID_FRAMING}
 *   <li>{@link #WRONG_VERSION} — MSH-12 declares 2.3 → {@code HL7_PARSE_UNSUPPORTED_VERSION}
 * </ul>
 *
 * <p>Provenance: hand-authored, synthetic-only.
 */
public final class Hl7Fixtures {

  /** Schema-valid ADT^A01 v2.5 admission with PID + PV1. */
  public static final String GOOD =
      "MSH|^~\\&|ADMIT_SYSTEM|HOSPITAL|RECEIVER|FACILITY|20260520143210||ADT^A01|MSG00001|P|2.5\r"
          + "EVN|A01|20260520143210\r"
          + "PID|1||MRN0001234^^^HOSP^MR||DOEPATIENT^JANE^ELIZABETH||19850203|F\r"
          + "PV1|1|I|2W^201^A^GEN|R||||DR_SMITH^WELBY^MARCUS|||||||||||||||||||||||||||||||"
          + "|||20260520143210";

  /** MSH header but PID segment is missing — PV1 follows EVN directly. */
  public static final String MISSING_PID =
      "MSH|^~\\&|ADMIT_SYSTEM|HOSPITAL|RECEIVER|FACILITY|20260520143210||ADT^A01|MSG00002|P|2.5\r"
          + "EVN|A01|20260520143210\r"
          + "PV1|1|I|2W^201^A^GEN|R";

  /**
   * Bad framing: missing segment-terminating carriage returns (line-feed only, plus a truncated
   * MSH-1 field separator). HL7 v2 requires {@code \r} segment terminators.
   */
  public static final String BAD_FRAMING =
      "MSH^^~\\&|ADMIT_SYSTEM|HOSPITAL|RECEIVER|FACILITY|20260520143210||ADT^A01|MSG00003|P|2.5\n"
          + "PID|1||MRN0001234^^^HOSP^MR||DOEPATIENT^JANE^ELIZABETH||19850203|F\n"
          + "PV1|1|I|2W^201^A^GEN";

  /** MSH-12 declares HL7 v2.3 — outside the {2.5, 2.5.1} support set. */
  public static final String WRONG_VERSION =
      "MSH|^~\\&|ADMIT_SYSTEM|HOSPITAL|RECEIVER|FACILITY|20260520143210||ADT^A01|MSG00004|P|2.3\r"
          + "EVN|A01|20260520143210\r"
          + "PID|1||MRN0001234^^^HOSP^MR||DOEPATIENT^JANE^ELIZABETH||19850203|F\r"
          + "PV1|1|I|2W^201^A^GEN|R";

  /** PHI tokens drawn from the fixtures above. Used by SC-005 assertions. */
  public static final java.util.List<String> PHI_TOKENS =
      java.util.List.of(
          "DOEPATIENT", "JANE", "ELIZABETH", "MRN0001234", "19850203", "DR_SMITH", "WELBY");

  private Hl7Fixtures() {}
}
