# Implementation Plan: ADT^A01 Ingestion and Inspection

**Branch**: `001-adt-a01-plan` (planning round following the merged `001-adt-a01-ingestion-inspection` spec branch) | **Date**: 2026-05-20 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `specs/001-adt-a01-ingestion-inspection/spec.md`

## Summary

Build a hub that accepts HL7 v2 ADT^A01 admission messages over HTTP, transforms them into FHIR R4 Patient and Encounter resources, exposes those resources over a small custom FHIR REST API, and ships with a web Inspector for listing, drilling into, and replaying messages. The backend is a single Spring Boot 4 application on Java 21 in the package `io.github.ramyagangapatnam.fhirhub`. The Inspector is an Angular 18 SPA. Persistence is Postgres 16; the audit sink is S3 with object-lock (Compliance) in AWS and an append-only JSONL file locally. Ingestion is a synchronous-persist / 202-accept handshake that hands the message to a transformation service through an explicit in-process seam (`InboundMessageProcessor`) that can later be swapped for SQS without API changes. Deployment is Docker Compose locally and ECS Fargate + RDS + S3 in AWS, provisioned by Terraform. Authentication is a static shared bearer token enforced by a Spring Security filter; the demo's single audit actor is `demo-operator`. This is explicitly a portfolio demo: scope is defended per constitution Principle X, and the README will state that the auth posture is demo-only.

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript 5.x via Angular 18 (frontend)
**Primary Dependencies**: Spring Boot 4.0.x, Spring Security 6.x, Spring Web, Spring Data JPA, HAPI HL7 v2 (`ca.uhn.hapi:hapi-base` + `hapi-structures-v25`), HAPI FHIR model classes (`ca.uhn.hapi.fhir:hapi-fhir-structures-r4`), Jackson, OpenTelemetry Java agent + SDK, Flyway (DB migrations), Testcontainers (integration tests), AWS SDK v2 (S3), Angular 18, RxJS
**Storage**: Postgres 16 (operational store: `inbound_message`, `validation_error`, `fhir_resource`, `idempotency_key`); S3 with Object Lock in Compliance mode (audit sink, AWS); local JSONL file under `var/audit/` (audit sink, local dev)
**Testing**: JUnit 5 + Mockito (unit), Spring Boot Test + Testcontainers (integration), REST-assured (HTTP contract tests), Karma/Jasmine (Angular unit), Playwright (Inspector end-to-end), Pact-style contract files committed under `specs/.../contracts/`
**Target Platform**: Linux containers (local: Docker Compose; AWS: ECS Fargate); browser (Angular SPA, modern evergreen)
**Project Type**: Web application (Spring Boot REST backend + Angular SPA + Postgres + S3 audit sink)
**Performance Goals**: 95th-percentile ingestion 202-Accepted under 500 ms (SC-001); 95th-percentile FHIR resource availability under 2 s after acknowledgement (SC-002); end-to-end correlation-ID traceability across 100% of sample messages (SC-009)
**Constraints**: No PHI in logs (Principle I); audit event for every PHI read/write/replay (Principle II); TLS 1.2+ for PHI in transit, KMS-managed encryption at rest (Principle III); test-first for HL7/FHIR/idempotency/audit core (Principle IV); demo scope defended (Principle X); ADR for every significant architectural choice (Principle XI)
**Scale/Scope**: Single-tenant portfolio demo. Expected sustained load: < 10 messages/sec, < 10k messages retained for an hour-long demo. Storage retention: lifetime of the demo environment (no automatic purge).

## Project Structure

### Documentation (this feature)

```text
specs/001-adt-a01-ingestion-inspection/
├── plan.md              # This file
├── spec.md              # Feature spec (input)
├── research.md          # Phase 0 — decision log
├── data-model.md        # Phase 1 — DDL and persistence contract
├── quickstart.md        # Phase 1 — 5-minute onboarding (SC-008)
└── contracts/           # Phase 1 — HTTP contracts + audit JSON schema
    ├── ingestion.openapi.yaml
    ├── fhir-read.openapi.yaml
    ├── inspector.openapi.yaml
    └── audit-event.schema.json
```

### Source Code (repository root)

```text
fhir-hub/
├── backend/                                              # Spring Boot 4 (Java 21)
│   ├── build.gradle.kts
│   ├── src/main/java/io/github/ramyagangapatnam/fhirhub/
│   │   ├── FhirHubApplication.java
│   │   ├── config/                                       # SecurityConfig, ObservabilityConfig, AuditConfig
│   │   ├── ingestion/                                    # IngestionController, IngestionService, size-cap filter
│   │   ├── hl7/                                          # HL7 v2 parser + ADT^A01 schema validator
│   │   ├── transform/                                    # HL7 → FHIR (Patient, Encounter) mappers
│   │   ├── fhir/                                         # FhirReadController (Patient, Encounter), FhirResourceRepository
│   │   ├── inspector/                                    # InspectorController (list, detail, replay)
│   │   ├── processing/                                   # InboundMessageProcessor (the seam)
│   │   ├── idempotency/                                  # IdempotencyKeyRepository, replay arbitration
│   │   ├── audit/                                        # AuditEventEmitter, S3AuditSink, FileAuditSink
│   │   ├── observability/                                # CorrelationIdFilter, MDC binding, manual spans
│   │   └── persistence/                                  # JPA entities, Flyway migrations under resources/db/migration/
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── db/migration/                                 # V1__inbound_message.sql, V2__validation_error.sql, ...
│   └── src/test/java/io/github/ramyagangapatnam/fhirhub/  # unit, integration (Testcontainers), contract
├── frontend/                                             # Angular 18 Inspector SPA
│   ├── angular.json
│   ├── package.json
│   └── src/app/
│       ├── inspector/                                    # list, detail (side-by-side), replay
│       ├── api/                                          # generated client from contracts/inspector.openapi.yaml
│       └── core/                                         # bearer-token interceptor, correlation-id propagation
├── test-client/                                          # CLI that POSTs canonical ADT^A01 fixtures (FR-020)
│   ├── build.gradle.kts (or thin shell script)
│   └── fixtures/                                         # good + bad ADT^A01 samples (synthetic only)
├── docker/
│   ├── docker-compose.yml                                # postgres, backend, frontend, localstack (S3)
│   └── Dockerfile.backend
├── infra/terraform/                                      # AWS IaC, runnable against an AWS account
│   ├── main.tf, variables.tf, outputs.tf
│   ├── modules/{network,rds,s3-audit,ecr,ecs-fargate,alb,iam}
│   └── environments/{dev}
├── docs/
│   ├── adr/                                              # ADRs (one per architectural decision)
│   └── FUTURE.md                                         # parked out-of-scope items (Principle X)
├── .github/workflows/                                    # ci.yml: lint, type-check, unit, integration, secret-scan
├── README.md
└── CLAUDE.md
```

**Structure Decision**: Web application layout with three top-level deliverables (`backend/`, `frontend/`, `test-client/`), plus `infra/`, `docker/`, and `docs/`. This matches the spec's three user-facing surfaces (ingestion HTTP, FHIR REST, Inspector UI) and keeps the Terraform and Docker Compose configurations near the code they deploy without coupling them to the Java module structure. The test client lives at the repo root so SC-008 (5-minute onboarding) doesn't require descending into a sub-module.

## 1. High-Level Component Diagram

```text
                           ┌──────────────────────────────────────────┐
                           │              Sender (curl / test-client) │
                           └────────────────────┬─────────────────────┘
                                                │  POST /ingest/hl7v2
                                                │  Bearer <token>
                                                │  Content-Type: application/hl7-v2
                                                ▼
                           ┌──────────────────────────────────────────┐
                           │             AWS ALB / local nginx        │
                           │             TLS terminate, size cap      │
                           └────────────────────┬─────────────────────┘
                                                │
                                                ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│                          Spring Boot 4 backend (single JVM)                      │
│                                                                                  │
│   ┌──────────────────────┐    ┌──────────────────────┐   ┌────────────────────┐  │
│   │ CorrelationIdFilter  │    │  AuthFilter (Spring  │   │  Body-size cap     │  │
│   │ (assigns/propagates) │──▶ │  Security, bearer)   │──▶│  + content-type    │  │
│   └──────────────────────┘    └──────────────────────┘   └─────────┬──────────┘  │
│                                                                    │             │
│   ┌──────────────────────────────────────────────────────────┐     │             │
│   │   IngestionController      ◀────────────────────────────-┘     │             │
│   │   ─ parse MSH only (lightweight) for idempotency key           │             │
│   │   ─ persist InboundMessage(raw, status=RECEIVED)               │             │
│   │   ─ return 202 with hub message id                             │             │
│   │   ─ hand off via InboundMessageProcessor.enqueue(messageId)    │             │
│   └────────────────────────────┬─────────────────────────────────-─┘             │
│                                │                                                 │
│                                ▼ (seam: in-process today, SQS-ready)             │
│   ┌──────────────────────────────────────────────────────────────┐               │
│   │   InboundMessageProcessor (interface)                        │               │
│   │     • InProcessInboundMessageProcessor (TaskExecutor)        │               │
│   │     • [future] SqsInboundMessageProcessor                    │               │
│   └────────────────────────────┬─────────────────────────────────┘               │
│                                ▼                                                 │
│   ┌──────────────────────────────────────────────────────────────┐               │
│   │   MessageTransformationService                               │               │
│   │     1. HL7 schema validation (ADT^A01, v2.5/v2.5.1)          │               │
│   │     2. transform → FHIR Patient + Encounter (HAPI models)    │               │
│   │     3. upsert via FhirResourceRepository (idempotent)        │               │
│   │     4. update InboundMessage status                          │               │
│   │     5. emit audit event via AuditEventEmitter                │               │
│   └────────────────────────────┬─────────────────────────────────┘               │
│                                │                                                 │
│         ┌──────────────────────┼─────────────────────────┐                       │
│         ▼                      ▼                         ▼                       │
│   ┌───────────┐         ┌─────────────────┐       ┌───────────────────┐          │
│   │  FHIR     │         │  Inspector      │       │  AuditEventEmitter│          │
│   │  Read     │         │  Controller     │       │     │             │          │
│   │  Ctrl     │         │  (list/detail/  │       │     ▼             │          │
│   │  (Patient,│         │   replay)       │       │  S3AuditSink (AWS)│          │
│   │  Encounter│         └────────┬────────┘       │  FileAuditSink    │          │
│   └─────┬─────┘                  │                │  (local JSONL)    │          │
│         │                        │                └────────┬──────────┘          │
│         ▼                        ▼                         ▼                     │
│   ┌─────────────────────────────────────────────────────────────────┐            │
│   │                  OpenTelemetry SDK + Java agent                 │            │
│   │     logs (JSON, MDC) ▸ metrics ▸ traces (W3C tracecontext)      │            │
│   └─────────────────────────────────────────────────────────────────┘            │
└──────────────────────────────────────────────────────────────────────────────────┘
              │                            │                           │
              ▼                            ▼                           ▼
       ┌────────────┐               ┌───────────────┐          ┌──────────────────┐
       │ Postgres 16│               │  S3 (Object   │          │  OTLP collector  │
       │ (RDS in    │               │  Lock,        │          │  (AWS: Managed   │
       │  AWS)      │               │  Compliance,  │          │  Prom + X-Ray;   │
       │            │               │  SSE-KMS)     │          │  local: console  │
       └────────────┘               └───────────────┘          │  exporter)       │
                                                                └──────────────────┘

                                                ▲
                                                │  HTTPS, bearer token
                                                │
                           ┌──────────────────────────────────────────┐
                           │   Angular 18 Inspector SPA               │
                           │   (served by backend in local dev;       │
                           │    served by S3+CloudFront or            │
                           │    backend in AWS — pick at deploy time) │
                           └──────────────────────────────────────────┘
```

**Boundaries**:

- **Sender ↔ ALB**: TLS terminates here; ALB enforces request size cap before bytes reach the JVM.
- **ALB ↔ Backend**: HTTPS in AWS; HTTP-on-localhost in Docker Compose. Bearer token is the auth boundary (Principle IX validation at boundary).
- **IngestionController ↔ InboundMessageProcessor**: explicit seam (§5). Today both sides are in the same JVM; the contract is small enough to swap for SQS later.
- **Backend ↔ Postgres**: JDBC; idempotency uniqueness enforced by DB constraint (Principle VIII).
- **Backend ↔ S3 audit sink**: append-only writes; bucket configured with Object Lock (Compliance mode) so the application cannot delete or overwrite (Principle II append-only property).
- **Backend ↔ OTel collector**: OTLP/gRPC.
- **Inspector SPA ↔ Backend**: HTTPS + bearer token; SPA never talks to Postgres or S3 directly.

## 2. Data Model (Postgres)

The full DDL with column types, indexes, and constraints lives in [data-model.md](./data-model.md). The summary here names the tables and the FHIR persistence approach.

**Operational tables**:

| Table | Purpose | Key columns |
|---|---|---|
| `inbound_message` | One row per ADT^A01 submission (valid or invalid) | `id` (UUID, pk), `raw_hl7` (text), `msh10_control_id`, `msh3_sending_application`, `received_at_utc`, `status` (enum: `RECEIVED`, `VALIDATING`, `TRANSFORMED`, `PERSISTED`, `FAILED`), `correlation_id`, `last_error_code`, `last_error_location` |
| `validation_error` | Structured validation/transformation errors, joined to `inbound_message` | `id` (UUID, pk), `inbound_message_id` (fk), `error_code`, `segment_field`, `summary_safe`, `created_at_utc` |
| `idempotency_key` | Enforces ingestion idempotency (Principle VIII) | `id` (UUID, pk), `sending_application`, `msh10_control_id`, `inbound_message_id` (fk), `patient_resource_id` (fk → fhir_resource.id, nullable), `encounter_resource_id` (fk → fhir_resource.id, nullable). Unique constraint on `(sending_application, msh10_control_id)`. |
| `fhir_resource` | FHIR Patient and Encounter resources | `id` (text, pk, FHIR-style logical id), `resource_type` (`Patient` \| `Encounter`), `version_id` (int, optimistic-lock), `last_updated_utc`, `content_json` (jsonb — serialized HAPI FHIR resource), `business_identifier_system`, `business_identifier_value`. Unique constraint on `(resource_type, business_identifier_system, business_identifier_value)`. |

**FHIR persistence approach — single `fhir_resource` table with JSONB content** (chosen over per-resource-type tables):

- **Why single table**: scope is exactly two resource types (Patient, Encounter, per Principle X). A per-type schema would require two near-identical migration paths and another schema change the moment Observation lands. JSONB serialization of HAPI FHIR resources is reversible (HAPI's `IParser` round-trips), lets us return wire-format JSON in the FHIR read controller without an ORM-to-FHIR layer, and keeps the persistence module thin.
- **Why not HAPI's bundled JPA server**: explicitly disallowed by the pre-decided tech stack; we want a small, custom controller surface and to keep the database under our migration control.
- **Indexed columns**: `(resource_type, business_identifier_system, business_identifier_value)` carries a unique constraint for Patient idempotency. `last_updated_utc` is indexed for Inspector list ordering joins where needed. Beyond that, no secondary search indexes — read-by-ID is the only required query.
- **Idempotency wiring**: the `idempotency_key` row is the source of truth for "have we seen this MSH-10 from this sender before?" It links the inbound message to the resulting Patient/Encounter IDs and is what the replay path consults before deciding whether to create or reuse FHIR resources.

## 3. API Contracts

OpenAPI definitions live under `contracts/`. Summary here.

### 3.1 Ingestion endpoint

- **Method/Path**: `POST /ingest/hl7v2`
- **Auth**: `Authorization: Bearer <token>` (Spring Security filter). Reject with `401` if missing or wrong.
- **Headers**: `Content-Type: application/hl7-v2` (custom media type — see ADR-0012 below). `X-Correlation-Id` (optional; if absent, server generates one and echoes it in the response).
- **Request body**: raw HL7 v2 message bytes (UTF-8, MLLP framing not used over HTTP — raw segments separated by `\r`). Max size: 64 KiB (typical ADT^A01 is ~2 KiB; cap enforced at the ALB and at a Spring filter for defense-in-depth).
- **Success response**: `202 Accepted` within 500 ms (SC-001).
  ```json
  {
    "messageId": "0f8fad5b-d9cb-469f-a165-70867728950e",
    "status": "RECEIVED",
    "receivedAtUtc": "2026-05-20T14:32:10.123Z",
    "correlationId": "9d2c3a8e-..."
  }
  ```
- **Error responses**:
  - `400 Bad Request` — malformed framing, missing required segment, version mismatch, encoding error, or size-cap exceeded. Body uses the **error envelope** (§3.7).
  - `401 Unauthorized` — missing/invalid bearer token. Body uses the error envelope; no echo of the supplied token.
  - `413 Payload Too Large` — explicit size-cap path (alternative to 400 for body-too-large).
  - `415 Unsupported Media Type` — wrong content type.
  - `500 Internal Server Error` — only for genuinely unexpected failures; body uses the error envelope and never contains a stack trace or PHI.

### 3.2 FHIR Patient read

- **Method/Path**: `GET /fhir/Patient/{id}`
- **Auth**: bearer token.
- **Headers**: `Accept: application/fhir+json` (default). `application/fhir+xml` is **out of scope** for this feature (deferred per Principle X; XML belongs in `FUTURE.md`).
- **Path params**: `id` (the FHIR logical id assigned at transformation time).
- **Success response**: `200 OK`, body is the FHIR R4 Patient resource (`resourceType: "Patient"` plus PID-derived fields per FR-006 / scenario 2). `ETag: W/"<version_id>"` and `Last-Modified` headers set.
- **Error responses**: `404 Not Found` (resource not yet persisted or never existed — error envelope wrapped in a FHIR `OperationOutcome` per FHIR conformance), `401`, `406 Not Acceptable` for unsupported `Accept`.

### 3.3 FHIR Encounter read

- **Method/Path**: `GET /fhir/Encounter/{id}`
- Behavior parallels §3.2; resource type is `Encounter`. The Encounter's `subject` field references the related Patient by logical id.

### 3.4 Inspector list

- **Method/Path**: `GET /inspector/messages`
- **Auth**: bearer token.
- **Query params**:
  - `status` (optional, repeatable): one of `RECEIVED|VALIDATING|TRANSFORMED|PERSISTED|FAILED`. FR-012.
  - `msh10` (optional): exact-match control-ID search. FR-012.
  - `limit` (default 50, max 200), `offset` (default 0).
- **Success response**: `200 OK`.
  ```json
  {
    "messages": [
      {
        "messageId": "0f8fad5b-...",
        "msh10ControlId": "MSG00001",
        "sendingApplication": "ADMIT_SYSTEM",
        "receivedAtUtc": "2026-05-20T14:32:10.123Z",
        "status": "PERSISTED",
        "lastErrorCode": null
      }
    ],
    "page": { "limit": 50, "offset": 0, "total": 1 }
  }
  ```
- **Error responses**: `400` invalid query (envelope), `401`.

### 3.5 Inspector detail

- **Method/Path**: `GET /inspector/messages/{messageId}`
- **Success response**: `200 OK`.
  ```json
  {
    "messageId": "0f8fad5b-...",
    "msh10ControlId": "MSG00001",
    "sendingApplication": "ADMIT_SYSTEM",
    "receivedAtUtc": "2026-05-20T14:32:10.123Z",
    "status": "PERSISTED",
    "rawHl7": "MSH|^~\\&|ADMIT_SYSTEM|...",
    "validationErrors": [],
    "fhirResources": {
      "patient": { "resourceType": "Patient", "id": "patient-uuid", "...": "..." },
      "encounter": { "resourceType": "Encounter", "id": "encounter-uuid", "...": "..." }
    },
    "correlationId": "9d2c3a8e-..."
  }
  ```
  When `status` is `FAILED`, `fhirResources` is `null` and `validationErrors` is populated. The raw HL7 body is present in the response because the operator is authenticated and the action is audited (FR-018 keeps PHI out of *logs and telemetry*, not out of an audited Inspector response).
- **Error responses**: `404` unknown id, `401`.

### 3.6 Inspector replay

- **Method/Path**: `POST /inspector/messages/{messageId}/replay`
- **Idempotency**: replay re-runs validation + transformation against the persisted raw HL7 body. FR-015 / FR-008. Replaying a `PERSISTED` message leaves resource counts unchanged and writes a separate audit event (FR-016, scenario 4).
- **Success response**: `200 OK`.
  ```json
  {
    "messageId": "0f8fad5b-...",
    "previousStatus": "FAILED",
    "newStatus": "PERSISTED",
    "validationErrors": [],
    "replayedAtUtc": "2026-05-20T14:45:01.882Z",
    "correlationId": "9d2c3a8e-..."
  }
  ```
- **Error responses**: `404` unknown id, `401`, `422 Unprocessable Entity` if the replay re-failed validation (body is the error envelope describing the new failure; the message status is updated to `FAILED` regardless of the HTTP status).

### 3.7 Error envelope

A single, stable shape across all non-FHIR endpoints. FR-004 / Principle IX (no PHI, no stack traces).

```json
{
  "error": {
    "code": "HL7_PARSE_MISSING_SEGMENT",
    "message": "Required segment PID was not present.",
    "location": "MSH|...|PID(missing)",
    "correlationId": "9d2c3a8e-..."
  }
}
```

- `code` is from a fixed, enumerated set committed to `contracts/error-codes.md`.
- `message` is summary-safe text; it MUST NOT contain field values from the source message.
- `location`, when present, names a segment / field index but never a value.
- FHIR endpoints wrap the same fields inside a `OperationOutcome` resource so the FHIR client side sees a conformant response.

## 4. Audit Event Shape and S3 Naming

### 4.1 JSON schema

The full JSON Schema is committed at `contracts/audit-event.schema.json`. Logical shape:

```json
{
  "auditId": "5b3f...uuid",
  "schemaVersion": "1",
  "timestampUtc": "2026-05-20T14:32:10.123Z",
  "actor": {
    "type": "operator | system",
    "identity": "demo-operator"
  },
  "correlationId": "9d2c3a8e-...",
  "resource": {
    "type": "InboundMessage | Patient | Encounter | AuditEvent",
    "id": "0f8fad5b-..."
  },
  "operation": "create | read | update | replay | delete",
  "outcome": "success | failure",
  "failureReason": { "code": "...", "summary": "..." },
  "sourceIp": "10.0.0.42",
  "userAgent": "test-client/0.1"
}
```

Required: `auditId`, `schemaVersion`, `timestampUtc`, `actor`, `correlationId`, `resource`, `operation`, `outcome`. Forbidden: any PHI field, any field value from the source HL7 message, any stack trace. `failureReason.summary` is constrained by the same allow-list as the error envelope's `message`.

### 4.2 S3 object naming and partitioning

- **Bucket**: `fhir-hub-audit-<env>` (e.g., `fhir-hub-audit-dev`). Provisioned by Terraform with:
  - Object Lock enabled, **Compliance** retention mode, default 7-year retention (revisit per Principle X if cost matters — but Compliance + tighter retention is the defensible default for an audit-trail demo).
  - SSE-KMS with a project-owned KMS key.
  - Block-public-access enforced.
  - Versioning enabled (required by Object Lock).
- **Key layout** (Hive-style, Athena-friendly):
  ```
  audit/year=YYYY/month=MM/day=DD/hour=HH/{auditId}.json
  ```
  e.g., `audit/year=2026/month=05/day=20/hour=14/5b3f....json`.
- **Object body**: one audit event per object, UTF-8 JSON conforming to the schema above. One-event-per-object (rather than newline-delimited batches) keeps the writer trivially append-only and means a future operator can revoke a single event's retention only by waiting out Compliance — which is the point.
- **Local dev sink**: a single JSONL file at `var/audit/audit.log`, opened in append-only mode (`O_APPEND` semantics). LocalStack S3 is wired up in `docker-compose.yml` for contributors who want to exercise the S3 path locally — see [quickstart.md](./quickstart.md).

## 5. Internal Processing Seam

The ingestion endpoint persists the raw HL7 body and immediately returns 202. Transformation and FHIR persistence happen behind an interface that hides whether the work runs in the same JVM or on a queue. The interface is intentionally narrow:

```java
package io.github.ramyagangapatnam.fhirhub.processing;

public interface InboundMessageProcessor {
    /**
     * Hand off responsibility for transforming and persisting the inbound message.
     * MUST NOT block waiting for transformation to complete.
     * MUST be safe to call from inside the HTTP request thread.
     * Implementations are responsible for preserving the correlation ID
     * across the hand-off (e.g., copying MDC into the worker context).
     */
    void enqueue(UUID inboundMessageId);
}
```

**Initial implementation — `InProcessInboundMessageProcessor`**:

- Submits to a bounded Spring `ThreadPoolTaskExecutor`.
- Captures the current MDC (correlation id, request id) and re-applies it inside the worker so log lines from transformation carry the same trace context.
- Pulls the row from `inbound_message`, walks the pipeline (validate → transform → upsert → audit), updates status.

**Future implementation — `SqsInboundMessageProcessor`** (deliberately not built now):

- `enqueue(messageId)` publishes a JSON envelope `{messageId, correlationId, traceparent}` to an SQS queue.
- A separate consumer (Lambda or worker in the same JVM polling SQS) pulls and runs the same `MessageTransformationService`.
- **No API change** is needed at `/ingest/hl7v2` — the controller still calls `processor.enqueue(...)` and returns 202.

**Seam invariants** (these are what the seam exists to enforce):

1. The 202 response is returned only after the raw body is durably persisted in `inbound_message`. The processor invocation is the trigger for downstream work, not the trigger for durability.
2. The seam call carries **only an identifier**, never the HL7 body. This is the key property that lets us put a real queue in place later without payload-size or PHI-in-queue concerns.
3. The seam is asynchronous from the controller's perspective. The controller MUST NOT block on the processor.
4. Correlation ID propagation is the implementation's responsibility (not the controller's), so swapping implementations doesn't break observability.

## 6. Observability Plan

### 6.1 What gets emitted, where

| Point | Logs (JSON) | Metrics | Traces (manual span) |
|---|---|---|---|
| HTTP server entry | one per request (method, path, status, duration, correlation_id) | `http.server.duration` (auto via OTel Spring instrumentation) | auto span: `http.server.request` |
| AuthFilter | one if rejected (no token echo) | `auth.reject.count` | inside the http.server span |
| IngestionController, before persistence | "ingest.received" with message hub id, correlation id | `hl7.ingest.count{outcome=received}` | manual span: `hl7.ingest` |
| HL7 parse + schema validate | "hl7.validate.ok" / "hl7.validate.fail" with error code + segment (no field values) | `hl7.validation.error.count{error_code}` | manual span: `hl7.validate` |
| Transform | "fhir.transform.ok" / "fhir.transform.fail" with message id (no resource fields) | `hl7.transform.duration`, `hl7.transform.count{outcome}` | manual span: `fhir.transform` |
| FHIR upsert | "fhir.persist.ok" with resource type + logical id | `fhir.persist.count{resource_type,outcome}` | manual span: `fhir.persist` (one per resource) |
| Idempotency check | "ingest.dedupe.hit" / "miss" | `ingest.dedupe.count{outcome}` | inside `fhir.persist` |
| Audit emit | "audit.emit" with audit id + resource type + operation (no PHI) | `audit.emit.count{operation,outcome,resource_type}` | manual span: `audit.emit` |
| Inspector list/detail | one per request, no body | `http.server.duration` (auto) | auto |
| Inspector replay | "replay.requested" / "replay.completed" with message id, prev/new status | `replay.count{outcome}` | manual span: `inspector.replay` |
| Background processor handoff | "processor.enqueue" with message id | `processor.enqueue.count` | links into the parent http.server span via traceparent |

**PHI redaction at the boundary (Principle I, SC-005)**: a Logback `MaskingConverter` and an OpenTelemetry `SpanProcessor` (`PhiAttributeSanitizer`) strip any attribute or message that matches the PHI-token list (names of fixture patients, MRNs from fixtures, date-of-birth patterns, plus any value of `raw_hl7` regardless of context). The sanitizer test suite asserts SC-005 by replaying canonical fixtures through the application and grepping the captured log/span output for known PHI tokens.

### 6.2 Correlation ID flow

```
client X-Correlation-Id (optional)
   │  if missing, CorrelationIdFilter generates a UUID v4
   ▼
HTTP request MDC + response header (echoed)
   ▼
InboundMessage.correlation_id  (persisted)
   ▼
InboundMessageProcessor (MDC copied into worker thread)
   ▼
TransformationService MDC + every log statement
   ▼
AuditEventEmitter → audit_event.correlationId
   ▼
W3C traceparent on every OTel span (parent-child preserved across the seam via context propagation)
```

This makes SC-009 (correlation ID present in every downstream artifact for 100% of sample messages) verifiable by a single end-to-end test: POST a message, grab the `X-Correlation-Id` from the response, then assert presence in: `inbound_message.correlation_id`, every line of the captured app log, every audit event in the sink, and every span in the exported trace.

### 6.3 OpenTelemetry instrumentation choices

- **Java agent**: attach `opentelemetry-javaagent.jar` at JVM start (Dockerfile sets `JAVA_TOOL_OPTIONS=-javaagent:/otel/opentelemetry-javaagent.jar`). This gives us auto-instrumentation for Spring MVC, Spring WebClient, JDBC, and the AWS SDK at zero code cost.
- **Manual spans**: created via the SDK in `MessageTransformationService`, `AuditEventEmitter`, `InspectorController#replay`, and the `InboundMessageProcessor` implementations — the points where business semantics live, not just HTTP semantics.
- **Exporter**: OTLP/gRPC. Locally points at a console-exporter sidecar in Docker Compose. In AWS, points at an ADOT collector running as a sidecar in the same ECS task, which fans out to CloudWatch Logs, CloudWatch Metrics, and AWS X-Ray.
- **Logs**: JSON layout (Logstash encoder), `correlation_id` and `traceparent` are MDC keys on every line. We do **not** ship OpenTelemetry logs as such — application logs go to stdout, ECS forwards to CloudWatch; the trace correlation is via the MDC trace IDs.
- **HTTP client instrumentation**: only used by the test client; covered by the agent.

## 7. Constitution Check

Each principle below names the specific plan element that implements it. Where coverage is partial, the gap is called out explicitly.

| # | Principle | Plan element(s) |
|---|---|---|
| I | PHI Confidentiality in Logs (NON-NEGOTIABLE) | §6.1 Logback `MaskingConverter` + OTel `PhiAttributeSanitizer`; explicit "no field values" rule on every log/metric/span row in §6.1; automated SC-005 test against the curated PHI token list. **No gap.** |
| II | PHI Access Auditing (NON-NEGOTIABLE) | §4 audit event shape and S3 sink (object-lock Compliance + KMS) is append-only and distinct from Postgres; every PHI-touching code path in §1's diagram routes through `AuditEventEmitter`; SC-006 is the verification check. **No gap.** |
| III | PHI Encryption at Rest and in Transit (NON-NEGOTIABLE) | §9 AWS topology: RDS encryption-at-rest (KMS), S3 SSE-KMS, TLS terminated at ALB with ACM cert; local-dev posture is HTTP-on-localhost only and is documented as such in the README. Test fixtures are synthetic-only (§3 Assumptions, §10 quickstart). **Partial gap, called out**: column-level encryption of `raw_hl7` is *not* in this plan — we rely on RDS volume encryption and on tight bucket/db credentials. An ADR (ADR-0014 below) will document this trade-off rather than silently leaving it. |
| IV | Test-First for Business Logic | §3 project structure carries `src/test/java/.../hl7`, `.../transform`, `.../idempotency`, `.../audit` directories; the contract files under `contracts/` are the basis for the first failing tests; the [tasks-template](../../.specify/templates/tasks-template.md) makes tests-first mandatory for these modules; SC-003 (idempotency), SC-005 (logs), SC-006 (audit) are testable acceptance criteria. **No gap.** |
| V | No Merging on Red | §3 includes `.github/workflows/ci.yml` (lint, type-check, unit, integration, secret-scan, dep-scan); ADR-0011 below pins the required-checks list. **No gap.** |
| VI | Secret Hygiene | Bearer token loaded from `FHIR_HUB_AUTH_TOKEN` env var; AWS Secrets Manager + ECS task secret injection in §9; gitleaks pre-commit + CI in §3; no token literal anywhere in the tree. **No gap.** |
| VII | Observability from Day One | §6 in its entirety: OTel agent at JVM start, manual spans on every business boundary, structured JSON logs with correlation ID, metrics on every meaningful counter. **No gap.** |
| VIII | Idempotent Ingestion | §2 `idempotency_key` table with unique `(sending_application, msh10_control_id)` constraint; `fhir_resource` unique constraint on `(resource_type, business_identifier_system, business_identifier_value)`; SC-003 verifies. **No gap.** |
| IX | Schema Validation at Boundaries | §1 AuthFilter + size-cap filter at the HTTP boundary; HAPI HL7 parser + explicit ADT^A01 schema validator at the message boundary; Bean Validation on Inspector DTOs; `@ConfigurationProperties` + `@Validated` on startup config. Error envelope (§3.7) ensures failures expose code-only, no PHI. **No gap.** |
| X | Demo Scope is Defended | This plan body lists *only* spec in-scope items; out-of-scope items go to `docs/FUTURE.md` (§3 structure) and to the ADR backlog (§8); the [tasks template](../../.specify/templates/tasks-template.md) was already aligned with Principle IV in commit `271fd86`. **No gap.** |
| XI | ADRs for Architectural Decisions | §8 enumerates the ADR backlog; `docs/adr/` is created as part of the source-code structure. **No gap.** |

**Gate result**: PASS with one partial gap on Principle III (column-level PHI encryption deferred, ADR-0014 will record the trade-off). No unjustified violations; no Complexity Tracking row required at this gate.

## 8. ADR Backlog

These ADRs MUST be created under `docs/adr/` (one file per decision, sequential numbering, kebab-title). Bodies are not written here — Phase 2 will produce them. The numbering below is the proposed initial order.

| ID | Title | Category |
|---|---|---|
| 0001 | HL7 v2 parser choice (HAPI HL7 v2) | dependency |
| 0002 | FHIR persistence approach (HAPI FHIR model classes + custom controllers, not HAPI's JPA server) | pre-decided architecture |
| 0003 | Database engine (Postgres 16) | dependency |
| 0004 | Frontend framework (Angular 18) | dependency |
| 0005 | Backend framework and language (Spring Boot 4.0.x on Java 21) | dependency |
| 0006 | IaC tool (Terraform) and container packaging (Docker / Docker Compose) | dependency |
| 0007 | Telemetry stack (OpenTelemetry agent + SDK; ADOT collector in AWS) | dependency |
| 0008 | Audit sink (S3 with Object Lock Compliance mode in AWS; JSONL file locally) | dependency |
| 0009 | Processing model (ingestion persists synchronously and hands off via an in-process seam; no real queue) | pre-decided architecture |
| 0010 | Authentication posture (static shared bearer token + `demo-operator` actor; explicitly demo-only) | pre-decided architecture |
| 0011 | CI required-checks list and branch protection (Principle V enforcement) | governance |
| 0012 | HTTP media type for HL7 ingestion (`application/hl7-v2` custom media type) | plan-introduced |
| 0013 | FHIR persistence layout (single `fhir_resource` table with JSONB content) | plan-introduced |
| 0014 | PHI-at-rest encryption posture (RDS volume + S3 SSE-KMS, no column-level encryption of `raw_hl7`) — deviation from Principle III strict reading | plan-introduced, principle deviation |
| 0015 | AWS compute target (ECS Fargate, not EC2 or App Runner) | pre-decided architecture |
| 0016 | Correlation-ID strategy (`X-Correlation-Id` header, server-assigned if absent, propagated through MDC and W3C traceparent) | plan-introduced |
| 0017 | Bearer-token storage and rotation (env var in local; AWS Secrets Manager in AWS) | plan-introduced |

Any expansion beyond the spec's "In scope" list (a new HL7 message type, a new transport, a new FHIR resource, RBAC, multi-tenancy) requires a separate ADR per Principle X. Items in `docs/FUTURE.md` are NOT plan items.

## 9. AWS Deployment Topology

```
            ┌─────────────────┐
            │  Route 53       │
            │  (optional)     │
            └────────┬────────┘
                     │
                     ▼
            ┌─────────────────┐         ┌──────────────┐
            │ ACM-managed     │         │   Operator   │
            │ TLS cert        │         │   browser    │
            └────────┬────────┘         └──────┬───────┘
                     │                         │
                     ▼                         │
        ┌──────────────────────────┐           │
        │  Application Load        │◀──────────┘
        │  Balancer (public)       │
        │  443 → backend           │
        └────────────┬─────────────┘
                     │
                     ▼
        ┌──────────────────────────────────────────────────┐
        │  ECS Fargate service: fhir-hub-backend           │
        │  ─ task: backend container + ADOT sidecar        │
        │  ─ desired count: 1 (scale-to-zero off-hours)    │
        │  ─ task role: read S3 audit bucket; read DB pwd  │
        │                from Secrets Manager              │
        │  ─ task execution role: pull from ECR, write logs│
        └──┬──────────────────────┬─────────────────┬──────┘
           │                      │                 │
           ▼                      ▼                 ▼
    ┌─────────────┐        ┌─────────────┐    ┌──────────────────┐
    │  RDS        │        │  S3 audit   │    │ CloudWatch Logs  │
    │  Postgres 16│        │  bucket     │    │ + Metrics +      │
    │  (private   │        │  Object Lock│    │ X-Ray (via ADOT) │
    │  subnet,    │        │  Compliance │    │                  │
    │  encrypted) │        │  SSE-KMS    │    │                  │
    └─────────────┘        └─────────────┘    └──────────────────┘

    ┌──────────────────────────────┐
    │  ECR repo: fhir-hub-backend  │
    │  (image pushed by GH Actions)│
    └──────────────────────────────┘

    Inspector SPA: served by the backend Spring Boot app
    (static resources, same origin). Avoids CloudFront + S3
    for the demo; see ADR-0015 for the SPA-hosting decision.
```

**Compute choice — ECS Fargate** (over EC2 or App Runner): Fargate gives us "deploy a container, no host management" with the smallest deviation from local Docker Compose, which is the right symmetry for a demo that runs identically in both places. App Runner is simpler but more expensive per CPU-hour and constrains networking choices (e.g., no straightforward placement inside the RDS-private VPC without extra plumbing). EC2 is cheaper at the long-running-instance scale but introduces patching, AMI choice, and an autoscaling group that adds operational surface this demo doesn't need to demonstrate. Free-tier note: Fargate has no free tier (EC2 t3.micro does, for 12 months), so the realistic ongoing cost of a continuously-running demo is roughly $10–15/month for a 0.25 vCPU / 0.5 GB task plus a `db.t4g.micro` (free-tier-eligible for 12 months) + a few cents of S3/CloudWatch. The Terraform exposes `desired_count = 0/1` and a `db_instance_class` variable so the demo can be powered down between sessions and stays well inside hobby-budget territory.

Networking: a small VPC with two private subnets (RDS, ECS tasks) and two public subnets (ALB, NAT — or NAT instance for cost). IAM is least-privilege: the task role can only `s3:PutObject` against the audit bucket prefix and `secretsmanager:GetSecretValue` against one specific secret ARN. No `*:*` policies.

## 10. Local-Dev Developer Experience (SC-008)

The full step-by-step is in [quickstart.md](./quickstart.md). The headline path — every command from clean clone to "I just saw my ingested message in the Inspector" — is:

```bash
# 1. Clone
git clone https://github.com/ramya-gangapatnam/fhir-hub.git
cd fhir-hub

# 2. Bring the stack up (Postgres + backend + frontend + localstack)
docker compose -f docker/docker-compose.yml up -d --build

# 3. Wait for backend health (≤ 30s typical, Compose health-check gates the others)
docker compose -f docker/docker-compose.yml ps

# 4. Send a known-good ADT^A01 fixture via the bundled test client
./test-client/post-fixture.sh fixtures/adt-a01-good.hl7

# 5. Open the Inspector
#    macOS:  open http://localhost:4200
#    Linux:  xdg-open http://localhost:4200
#    Win:    start http://localhost:4200
```

That is the entire 5-minute path. The Inspector loads, the operator sees the just-ingested message with status `PERSISTED`, clicks in for the side-by-side raw HL7 / FHIR JSON view, and SC-008 is satisfied. The test client also has a `--bad` flag (`./test-client/post-fixture.sh fixtures/adt-a01-missing-pid.hl7`) so the same five-minute walkthrough demonstrates the failed-message + replay path.

## Complexity Tracking

No unjustified Constitution Check violations. Principle III has a partial-coverage note (no column-level PHI encryption) tracked as ADR-0014; this is recorded in the ADR backlog rather than as a plan complexity item because it's a transparent trade-off with a documented rationale, not a deviation introduced by the plan's structure.
