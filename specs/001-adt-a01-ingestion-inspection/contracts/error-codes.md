# Stable Error Codes

The error envelope's `code` field (and the audit event's `failureReason.code`)
draws from this fixed allow-list. Adding a code requires updating this file.
Codes are SCREAMING_SNAKE_CASE and prefixed by domain.

## HTTP boundary

| Code | When |
|---|---|
| `HTTP_AUTH_MISSING_TOKEN` | No `Authorization` header. |
| `HTTP_AUTH_INVALID_TOKEN` | Bearer token does not match the configured value. |
| `HTTP_UNSUPPORTED_MEDIA_TYPE` | `Content-Type` is not `application/hl7-v2` on the ingestion endpoint. |
| `HTTP_PAYLOAD_TOO_LARGE` | Request body exceeds the 64 KiB cap. |
| `HTTP_NOT_FOUND` | Resource not found (path id has no matching row). |
| `HTTP_INTERNAL_ERROR` | Catch-all; logged with full stack trace internally but the response carries this code only. |

## HL7 parse and validation

| Code | When |
|---|---|
| `HL7_PARSE_INVALID_FRAMING` | Invalid segment terminator, wrong field separator, truncated body. |
| `HL7_PARSE_MISSING_SEGMENT` | Required segment (MSH, PID, PV1) absent. `location` names the missing segment. |
| `HL7_PARSE_MISSING_REQUIRED_FIELD` | Required field within a present segment absent. `location` is `<SEG>-<idx>`. |
| `HL7_PARSE_UNSUPPORTED_VERSION` | MSH-12 declares a version other than 2.5/2.5.1. |
| `HL7_PARSE_UNSUPPORTED_MESSAGE_TYPE` | MSH-9 declares a message type other than ADT^A01. |
| `HL7_PARSE_ENCODING_ERROR` | Body contains bytes outside the declared encoding. |

## Transformation

| Code | When |
|---|---|
| `FHIR_TRANSFORM_UNMAPPABLE_FIELD` | A required FHIR target field has no source data and no default mapping. |
| `FHIR_TRANSFORM_INTERNAL_ERROR` | Catch-all for transformation failures not classified above. |

## Persistence and idempotency

| Code | When |
|---|---|
| `PERSIST_IDEMPOTENCY_CONFLICT` | Race between two concurrent POSTs of the same message; the loser produces this code internally and the application converts it into an idempotent success response. Not seen by callers. |
| `PERSIST_DB_UNAVAILABLE` | Postgres connection failed; the request is retriable. |
| `PERSIST_S3_UNAVAILABLE` | Audit sink unreachable; the request is retriable. Note: a failure to emit an audit event is itself an outcome-failure audit-event candidate, but if the sink is down, the system logs (no PHI) and surfaces this code. |

## Inspector and replay

| Code | When |
|---|---|
| `INSPECTOR_MESSAGE_NOT_FOUND` | The `messageId` does not match any row in `inbound_message`. |
| `REPLAY_REVALIDATION_FAILED` | Replay re-ran validation and the message is still invalid. Body is the new validation error envelope; HTTP status is 422. |
