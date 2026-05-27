# Fixtures — provenance and intent

**Provenance: hand-authored, synthetic-only.** No fixture in this directory describes a real
person, encounter, or facility. Names, MRNs, dates of birth, and provider identifiers are
invented for the purposes of US1 / US2 demonstration.

Per Constitution Principle III (PHI Encryption at Rest and in Transit) and the spec's
synthetic-data sourcing rule, no production or de-identified-but-real HL7 v2 traffic may be
added to this directory.

## Fixtures

| File | Intent | Expected outcome |
|---|---|---|
| `adt-a01-good.hl7`         | Schema-valid ADT^A01 v2.5 admission with PID + PV1.                  | 202 Accepted, then Patient + Encounter readable via `/fhir/...`. |
| `adt-a01-missing-pid.hl7`  | Required PID segment absent — PV1 follows EVN.                       | 400 + `HL7_PARSE_MISSING_SEGMENT`. |
| `adt-a01-bad-framing.hl7`  | Broken MSH-1 field separator (`^` instead of `|`).                   | 400 + `HL7_PARSE_INVALID_FRAMING`. |
| `adt-a01-wrong-version.hl7`| MSH-12 declares HL7 v2.3, outside the supported set `{2.5, 2.5.1}`.  | 400 + `HL7_PARSE_UNSUPPORTED_VERSION`. |

The `post-fixture.sh` script normalizes line endings to CR before posting (HL7 v2 mandates
`\r` segment terminators); store these files as plain text on whatever platform.
