# Tasks: ADT^A01 Ingestion and Inspection

**Input**: Design documents from `specs/001-adt-a01-ingestion-inspection/`
**Prerequisites**: [plan.md](./plan.md) (required), [spec.md](./spec.md) (required for user stories), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/](./contracts/)
**Constitution**: [.specify/memory/constitution.md](../../.specify/memory/constitution.md) (v1.0.0)

**Tests**: Per Principle IV (Test-First for Business Logic), tests are MANDATORY for HL7 parsing, FHIR mapping, validation, idempotency, and audit emission — and MUST fail before the matching implementation lands. Tests are OPTIONAL for pure infrastructure scaffolding (Gradle wiring, Angular scaffold, Compose, CI skeleton, Flyway DDL).

**Organization**: Tasks are grouped by user story so each story can be implemented and validated independently. Both spec stories are P1; US1 (ingestion + FHIR read) is the foundation that makes US2 (Inspector) demonstrable, so US1 ships first and US2 layers on top.

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: Can run in parallel with other [P]-tagged tasks in the same phase (touches different files, no ordering dependency on unfinished tasks)
- **[Story]**: `[US1]` ingestion+FHIR read, `[US2]` Inspector; Setup / Foundational / Polish tasks carry no story label
- Constitution principles are cited inline on every task that touches PHI, audit, logging, security, idempotency, or boundary validation (per the user's review instruction)

## Path Conventions

Per [plan.md §3](./plan.md), the repository is a web-app layout:

- Backend Java sources: `backend/src/main/java/io/github/ramyagangapatnam/fhirhub/`
- Backend Java tests: `backend/src/test/java/io/github/ramyagangapatnam/fhirhub/`
- Backend resources (incl. Flyway): `backend/src/main/resources/`
- Frontend (Angular 18): `frontend/src/app/`
- Test client + fixtures: `test-client/`
- Compose + Dockerfiles: `docker/`
- Terraform IaC: `infra/terraform/`
- ADRs: `docs/adr/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Get the repository skeleton, build tools, and local Compose stack in place so MVP work can start. No business logic here.

- [X] T001 Create top-level directory skeleton: `backend/`, `frontend/`, `test-client/`, `docker/`, `infra/terraform/`, `docs/adr/`, `docs/FUTURE.md` per [plan.md §3](./plan.md). Add an `.editorconfig` and project-root `.gitignore` that excludes `var/audit/`, `node_modules/`, `build/`, `target/`, `.gradle/`.
- [X] T002 Backend Gradle build at `backend/build.gradle.kts` and `backend/settings.gradle.kts` pinning Java 21 + Spring Boot 4.0.x and declaring dependencies from [plan.md §Technical Context](./plan.md): Spring Web, Spring Security 6, Spring Data JPA, Flyway, HAPI HL7 `hapi-base` + `hapi-structures-v25`, HAPI FHIR `hapi-fhir-structures-r4`, Jackson, OpenTelemetry SDK + agent, AWS SDK v2 (S3), JUnit 5, Mockito, Spring Boot Test, Testcontainers, REST-assured. Wire `spotless`/`checkstyle` for lint.
- [X] T003 [P] Frontend Angular 18 scaffold at `frontend/` (Angular CLI `ng new` output: `angular.json`, `package.json`, `tsconfig.json`, `src/main.ts`, `src/index.html`, baseline `src/app/app.component.*` and `src/app/app.config.ts`). Add `eslint` config; do NOT add any inspector components yet (those land in Phase 4).
- [X] T004 [P] Docker Compose stack at `docker/docker-compose.yml` + `docker/Dockerfile.backend`: services `postgres` (postgres:16), `backend` (built from Dockerfile, JVM with `-javaagent:/otel/opentelemetry-javaagent.jar`), `frontend` (Angular dev server on :4200), `localstack` (S3 only). Health-check on `backend` gates `frontend`. Bind-mount `./var/audit` into backend so the local JSONL audit sink is host-readable.
- [X] T005 GitHub Actions skeleton at `.github/workflows/ci.yml` with jobs: `lint`, `type-check`, `unit-test`, `integration-test`, `secret-scan`, `dep-scan` — wired as required checks. **Principle V** (No Merging on Red): all six jobs MUST be required for merge to `main`.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Schema, auth, correlation, redaction, and the error envelope. Every user story depends on these. **No US1 or US2 work begins until this phase is checkpoint-green.**

- [X] T006 Flyway migration `backend/src/main/resources/db/migration/V1__inbound_message.sql`: `inbound_message` table per [data-model.md §2.1](./data-model.md) with all columns, the four indexes (`received_at_desc`, `status`, `msh10_control_id`, `correlation_id`), and the `status` CHECK constraint.
- [X] T007 Flyway migration `V2__validation_error.sql`: `validation_error` table per [data-model.md §2.2](./data-model.md) with FK + index.
- [X] T008 Flyway migration `V3__fhir_resource.sql`: `fhir_resource` table per [data-model.md §2.4](./data-model.md), including the partial unique index on `(resource_type, business_identifier_system, business_identifier_value)` and `idx_fhir_resource_type_id`. **Principle VIII** (idempotency at the persistence layer).
- [X] T009 Flyway migration `V4__idempotency_key.sql`: `idempotency_key` table per [data-model.md §2.3](./data-model.md), with the `UNIQUE (sending_application, msh10_control_id)` constraint and FKs to `inbound_message` and `fhir_resource`. **Principle VIII**.
- [X] T010 [P] Flyway migration `V5__functions_triggers.sql`: shared `set_updated_at_utc()` trigger function and `BEFORE UPDATE` triggers on `inbound_message`, `idempotency_key`.
- [X] T011 Spring Security bearer-token filter at `backend/src/main/java/io/github/ramyagangapatnam/fhirhub/config/SecurityConfig.java` + a filter class under `ingestion/AuthFilter.java`. Token loaded from `FHIR_HUB_AUTH_TOKEN` env var, never logged or echoed. Reject with `HTTP_AUTH_MISSING_TOKEN` / `HTTP_AUTH_INVALID_TOKEN` via the error envelope. **Principle VI** (Secret Hygiene), **Principle IX** (Schema Validation at Boundaries).
- [X] T012 `observability/CorrelationIdFilter.java`: read `X-Correlation-Id` from request or generate a UUID v4, bind to MDC under `correlation_id`, echo on response. **Principle VII** (Observability from Day One).
- [X] T013 `ingestion/IngestionBoundaryFilter.java`: enforce 64 KiB body cap (→ `HTTP_PAYLOAD_TOO_LARGE`) and `Content-Type: application/hl7-v2` (→ `HTTP_UNSUPPORTED_MEDIA_TYPE`) on `/ingest/hl7v2`. **Principle IX**.
- [X] T014 Logback PHI `MaskingConverter` at `observability/MaskingConverter.java` + `logback-spring.xml` JSON layout in `backend/src/main/resources/`. Mask the curated PHI token list and any `raw_hl7` value at the emission boundary. **Principle I** (PHI Confidentiality in Logs — NON-NEGOTIABLE).
- [X] T015 OpenTelemetry wiring: `config/ObservabilityConfig.java` exposing the SDK + manual `Tracer` bean, and `observability/PhiAttributeSanitizer.java` `SpanProcessor` that strips PHI-matching attributes before export. OTLP/gRPC exporter configurable via env. **Principle I**, **Principle VII**.
- [X] T016 [P] Global error envelope handler at `config/ErrorEnvelopeAdvice.java` (`@RestControllerAdvice`) producing the [plan.md §3.7](./plan.md) JSON shape, plus an `error/ErrorCode.java` enum mirroring [contracts/error-codes.md](./contracts/error-codes.md). Stack traces NEVER appear in responses; `correlationId` is always populated. **Principle IX**.

**Checkpoint**: schema migrates cleanly against a Testcontainers Postgres; the backend starts; an unauthorized request to any path returns the error envelope with code `HTTP_AUTH_MISSING_TOKEN` and no PHI; correlation IDs round-trip on the response header.

---

## Phase 3: User Story 1 — HL7 ADT^A01 Ingestion + FHIR Read (Priority: P1) 🎯 MVP

**Goal**: A sender POSTs an ADT^A01 message to `/ingest/hl7v2`, receives `202` with a hub message id within 500 ms, and within 2 seconds can `GET /fhir/Patient/{id}` and `GET /fhir/Encounter/{id}` to retrieve the transformed FHIR resources. Idempotent on `(MSH-3, MSH-10)`.

**Independent Test**: From a clean stack, POST `test-client/fixtures/adt-a01-good.hl7`, assert `202 Accepted` and a `messageId` body. Poll `GET /fhir/Patient/{id}` until `200`. Assert the Patient body matches the PID fields (name, identifier, DOB, sex). POST the same fixture again; assert no new Patient/Encounter rows exist (per SC-003). Verify `var/audit/audit.log` has one create-Patient + one create-Encounter event for the first POST and only a replay event for the second.

### Tests for User Story 1 (MANDATORY per Principle IV) ⚠️

> Write these tests FIRST and ensure they FAIL before implementing T028–T041.

- [X] T017 [P] [US1] Contract test happy-path POST `/ingest/hl7v2` at `backend/src/test/java/.../ingestion/IngestionContractTest.java`, driven from [contracts/ingestion.openapi.yaml](./contracts/ingestion.openapi.yaml): asserts `202`, response schema (`messageId`, `status`, `receivedAtUtc`, `correlationId`), and that the `correlationId` echoes the request header.
- [X] T018 [P] [US1] Contract test ingestion error paths at `IngestionErrorContractTest.java`: missing PID → 400 + `HL7_PARSE_MISSING_SEGMENT`; bad framing → 400 + `HL7_PARSE_INVALID_FRAMING`; wrong version → 400 + `HL7_PARSE_UNSUPPORTED_VERSION`; oversized → 413 + `HTTP_PAYLOAD_TOO_LARGE`; wrong content-type → 415 + `HTTP_UNSUPPORTED_MEDIA_TYPE`. Each assertion verifies the response body contains zero PHI tokens. **Principle I**, **Principle IX**.
- [X] T019 [P] [US1] Contract test `GET /fhir/Patient/{id}` at `fhir/FhirPatientReadContractTest.java`, driven from [contracts/fhir-read.openapi.yaml](./contracts/fhir-read.openapi.yaml): `200` returns a FHIR R4 Patient with `ETag` + `Last-Modified`; unknown id returns FHIR `OperationOutcome` with status `404`; unsupported `Accept` returns `406`.
- [X] T020 [P] [US1] Contract test `GET /fhir/Encounter/{id}` at `fhir/FhirEncounterReadContractTest.java`: parallel coverage to T019 plus assertion that `Encounter.subject` references the related Patient logical id.
- [X] T021 [P] [US1] Unit test ADT^A01 schema validator at `hl7/Adt01SchemaValidatorTest.java`: asserts MSH/PID/PV1 presence, MSH-9 = `ADT^A01`, MSH-12 in `{2.5, 2.5.1}`, and the minimum required fields per [plan.md §3.1](./plan.md). Each failure path resolves to the exact `HL7_PARSE_*` code from [error-codes.md](./contracts/error-codes.md). **Principle IX**.
- [X] T022 [P] [US1] Unit test PID → FHIR Patient mapper at `transform/PidToPatientMapperTest.java`: name (PID-5), identifier (PID-3 with system), birth date (PID-7), sex (PID-8). Asserts that mapped Patient round-trips through HAPI `IParser` to JSON without loss.
- [X] T023 [P] [US1] Unit test PV1 → FHIR Encounter mapper at `transform/Pv1ToEncounterMapperTest.java`: class (PV1-2), admission type (PV1-4), location (PV1-3), attending provider reference (PV1-7), and `subject` reference to the Patient id produced by T022's mapper.
- [X] T024 [US1] Integration test idempotency at `idempotency/IdempotencyIntegrationTest.java` (Testcontainers Postgres): POST the same good fixture three times concurrently; assert exactly one `inbound_message` row per POST, exactly one `idempotency_key` row total, exactly one Patient and one Encounter row, and that all three responses return `202` with the same downstream resource ids. Covers SC-003. **Principle VIII**.
- [X] T025 [US1] Integration test PHI-not-in-logs at `observability/PhiRedactionIntegrationTest.java`: replay every fixture in `test-client/fixtures/` through the stack, capture the JSON log output via a test-scoped Logback appender + OTel `InMemorySpanExporter`, and grep both against the curated PHI token list. Asserts zero hits. Covers SC-005. **Principle I**.
- [X] T026 [US1] Integration test audit emission at `audit/AuditEmissionIntegrationTest.java`: assert one `create` audit event per Patient and per Encounter (FR-016) for an ingest, one `read` audit event per FHIR REST GET; assert the audit JSONL conforms to [contracts/audit-event.schema.json](./contracts/audit-event.schema.json); assert no PHI tokens appear in any audit body. Covers SC-006. **Principle II** (PHI Access Auditing — NON-NEGOTIABLE).
- [X] T027 [US1] Integration test correlation-ID propagation at `observability/CorrelationIdPropagationTest.java`: POST a fixture with a fixed `X-Correlation-Id`, assert the same id appears in the response header, in `inbound_message.correlation_id`, in every captured log line, in every span, and in the audit event written for that message. Covers SC-009. **Principle VII**.

### Implementation for User Story 1

- [X] T028 [US1] JPA entities + Spring Data repositories under `persistence/`: `InboundMessage`, `ValidationError`, `IdempotencyKey`, `FhirResource` with `JdbcType.JSON` for `content_json`. Map the enum statuses to the DB CHECK values exactly.
- [X] T029 [US1] HL7 lightweight parser at `hl7/Hl7HeaderParser.java`: extracts MSH-3, MSH-9, MSH-10, MSH-12 only, fast enough to run inside the request thread before persistence. Uses HAPI HL7 `PipeParser` in lenient mode for header-only extraction. **Principle IX**.
- [X] T030 [US1] ADT^A01 schema validator at `hl7/Adt01SchemaValidator.java` (full validation, NOT the lightweight header parse): produces `ValidationError` records keyed to [error-codes.md](./contracts/error-codes.md). **Principle IX**.
- [X] T031 [P] [US1] PID → Patient mapper at `transform/PidToPatientMapper.java` using HAPI FHIR R4 model classes. Returns a populated `org.hl7.fhir.r4.model.Patient`.
- [X] T032 [P] [US1] PV1 → Encounter mapper at `transform/Pv1ToEncounterMapper.java`. Sets `Encounter.subject` to the Patient logical id.
- [X] T033 [US1] `fhir/FhirResourceRepository.java` upsert + read-by-id: serializes HAPI resources via `IParser`, bumps `version_id`, sets `last_updated_utc`. Used by ETag emission. Honors the unique index on `(resource_type, business_identifier_system, business_identifier_value)` (T008). **Principle VIII**.
- [X] T034 [US1] `idempotency/IdempotencyArbiter.java`: insert-or-fetch on `idempotency_key` using `INSERT ... ON CONFLICT DO NOTHING RETURNING ...`; on conflict, return the existing row's Patient/Encounter ids unchanged. Converts `PERSIST_IDEMPOTENCY_CONFLICT` into an idempotent success. **Principle VIII**.
- [X] T035 [US1] `transform/MessageTransformationService.java`: orchestrates `validate → transform → upsert → audit → status update` against a single `inbound_message_id`. Emits one audit event per resource write via `AuditEventEmitter`. **Principle II**, **Principle VIII**.
- [X] T036 [US1] `processing/InboundMessageProcessor.java` (interface) + `processing/InProcessInboundMessageProcessor.java` implementing the seam from [plan.md §5](./plan.md): bounded `ThreadPoolTaskExecutor`, copies MDC into the worker, calls `MessageTransformationService.process(messageId)`. Carries ONLY the id — never the HL7 body — across the seam. **Principle VII**.
- [ ] T037 [US1] `ingestion/IngestionController.java` `POST /ingest/hl7v2`: lightweight MSH parse (T029), persist `inbound_message` with `status=RECEIVED`, call `processor.enqueue(messageId)`, return `202` within 500 ms. Emits a `create` audit event for the `InboundMessage` write. **Principle II**, **Principle VII**.
- [ ] T038 [US1] `fhir/FhirReadController.java` `GET /fhir/Patient/{id}` and `GET /fhir/Encounter/{id}`: streams `content_json` straight back as `application/fhir+json` (no re-parse), sets `ETag: W/"<version_id>"`, `Last-Modified`. 404 returns an `OperationOutcome` wrapping the error envelope. Emits one `read` audit event per request. **Principle II**.
- [ ] T039 [US1] `audit/AuditEventEmitter.java` + `audit/FileAuditSink.java`: JSONL append (`O_APPEND` semantics) at `var/audit/audit.log`; the emitter enforces the schema, fills `auditId`, `schemaVersion`, `timestampUtc`, `correlationId` from MDC. **Principle II** (append-only, distinct sink).
- [ ] T040 [US1] `audit/S3AuditSink.java`: writes one event per object using the Hive-partitioned key layout from [plan.md §4.2](./plan.md). Selected via `AUDIT_SINK=s3` env. Wired against LocalStack in dev via `docker-compose.yml`. **Principle II**, **Principle III** (encryption at rest via SSE-KMS in AWS).
- [ ] T041 [US1] Test client at `test-client/post-fixture.sh` (POSIX shell + curl) and synthetic fixtures under `test-client/fixtures/`: `adt-a01-good.hl7`, `adt-a01-missing-pid.hl7`, `adt-a01-bad-framing.hl7`, `adt-a01-wrong-version.hl7`. Fixtures contain only synthetic data with a `# Provenance: hand-authored, synthetic-only` header comment per the constitution's Synthetic data sourcing rule. **Principle III**, FR-020.

**Checkpoint US1**: A clean Compose stack accepts the good fixture, returns 202, exposes `/fhir/Patient/{id}` and `/fhir/Encounter/{id}` within 2 seconds, and a repeat POST produces zero duplicates. The MVP demo path works end-to-end without the Inspector.

---

## Phase 4: User Story 2 — Inspector UI (Priority: P1)

**Goal**: An operator lists ingested messages, drills into any one to see raw HL7 side-by-side with the derived FHIR resources, and replays failed messages from the UI. Replays are idempotent on the underlying ingestion key and emit a distinct audit event.

**Independent Test**: With US1 working, POST a good fixture and a bad fixture. Open `http://localhost:4200`. Both messages appear in the list with correct statuses. Click the good one — raw HL7 left, FHIR Patient + Encounter JSON right, no validation errors panel. Click the bad one — validation-error panel shows the structured error. Click **Replay** on the bad one (with the body still bad): status stays `FAILED`, a new replay audit event is written. Click **Replay** on the good one: resource counts unchanged, distinct replay audit event recorded.

### Tests for User Story 2 (MANDATORY per Principle IV for replay + audit) ⚠️

- [ ] T042 [P] [US2] Contract test `GET /inspector/messages` at `backend/src/test/java/.../inspector/InspectorListContractTest.java`, driven from [contracts/inspector.openapi.yaml](./contracts/inspector.openapi.yaml): pagination, `status` filter (single + repeated), `msh10` exact-match search, default `received_at desc` order. Asserts response body never contains `raw_hl7` (list view is metadata-only).
- [ ] T043 [P] [US2] Contract test `GET /inspector/messages/{messageId}` at `InspectorDetailContractTest.java`: 200 happy path with `rawHl7` + `fhirResources.patient` + `fhirResources.encounter`; failed-message variant with `fhirResources = null` and `validationErrors[]` populated; 404 unknown id.
- [ ] T044 [P] [US2] Contract test `POST /inspector/messages/{messageId}/replay` at `InspectorReplayContractTest.java`: 200 on success with `previousStatus`/`newStatus`/`replayedAtUtc`; 422 + `REPLAY_REVALIDATION_FAILED` when the persisted body is still invalid; 404 unknown id.
- [ ] T045 [US2] Integration test replay idempotency + audit at `inspector/ReplayIdempotencyIntegrationTest.java`: replay a `PERSISTED` good message 5 times, assert Patient + Encounter row counts unchanged; assert exactly 5 distinct `replay` audit events written to the JSONL sink; assert each replay event carries `operation=replay` and the same `correlationId` as the originating ingestion (or a new one bound to the operator action, per the implementation's choice — the test pins the chosen contract). **Principle II**, **Principle VIII**.

### Implementation for User Story 2 — Backend

- [ ] T046 [US2] `inspector/InspectorController.java#list` for `GET /inspector/messages`: status filter, `msh10` exact-match, pagination (`limit` default 50 max 200, `offset`). Returns the list-view DTO from [plan.md §3.4](./plan.md); raw HL7 is NEVER returned by the list endpoint.
- [ ] T047 [US2] `InspectorController#detail` for `GET /inspector/messages/{messageId}`: returns raw HL7 + transformed FHIR Patient/Encounter + validation errors per [plan.md §3.5](./plan.md). Emits a `read` audit event for the `InboundMessage` AND for each FHIR resource returned. **Principle II**.
- [ ] T048 [US2] `InspectorController#replay` for `POST /inspector/messages/{messageId}/replay`: pulls the persisted `raw_hl7`, calls `MessageTransformationService.process(messageId)` synchronously (or via the seam — either is acceptable; the chosen path is documented in an ADR per Principle XI), emits a distinct `replay` audit event, returns the [plan.md §3.6](./plan.md) DTO. **Principle II**, **Principle VIII**.

### Implementation for User Story 2 — Frontend

- [ ] T049 [P] [US2] Angular generated API client at `frontend/src/app/api/` from [contracts/inspector.openapi.yaml](./contracts/inspector.openapi.yaml) (`openapi-generator-cli` with `typescript-angular`). Build step wired in `package.json`.
- [ ] T050 [P] [US2] `frontend/src/app/core/auth.interceptor.ts` (bearer-token from runtime config) and `frontend/src/app/core/correlation-id.interceptor.ts` (generate + propagate `X-Correlation-Id`). Registered in `app.config.ts`. **Principle VI**, **Principle VII**.
- [ ] T051 [US2] Inspector list view at `frontend/src/app/inspector/list/` (`message-list.component.ts/.html/.scss`): table with messageId, msh10, sending application, received-at UTC, status; status filter (multi-select), msh10 search box; rows clickable to detail.
- [ ] T052 [US2] Inspector detail view at `frontend/src/app/inspector/detail/`: two-pane layout, raw HL7 on the left (preserving segment line breaks), FHIR Patient + Encounter JSON pretty-printed on the right; validation-error panel below when `status=FAILED`.
- [ ] T053 [US2] Replay button on the detail view + a small `inspector.service.ts` method calling `POST /inspector/messages/{id}/replay`; on success, re-fetch the detail and surface the new status; on 422 show the new validation error. Adds a Karma/Jasmine unit test for the service.

**Checkpoint US2**: An operator can list, drill into, and replay messages via the browser. Both stories independently demonstrable.

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: ADR-driven decision records, deployment artifacts, the CI hardening pass, and the explicit SC-008 onboarding validation.

- [ ] T054 [P] ADR backlog batch 1 (dependency + pre-decided architecture decisions) under `docs/adr/`: ADR-0001 HL7 parser choice, 0002 FHIR persistence approach, 0003 Postgres 16, 0004 Angular 18, 0005 Spring Boot 4 on Java 21, 0006 Terraform + Docker, 0007 OpenTelemetry, 0008 audit sink (S3 Object Lock + JSONL). One file per ADR per [plan.md §8](./plan.md). **Principle XI**.
- [ ] T055 [P] ADR backlog batch 2 (plan-introduced + principle-deviation decisions): ADR-0009 processing seam, 0010 auth posture (demo-only), 0011 CI required checks, 0012 `application/hl7-v2` media type, 0013 single-table JSONB FHIR persistence, 0014 PHI-at-rest encryption posture (Principle III deviation — RDS volume + S3 SSE-KMS, no column encryption), 0015 ECS Fargate, 0016 correlation-ID strategy, 0017 bearer-token storage + rotation. **Principle XI**, **Principle III**.
- [ ] T056 Terraform skeleton at `infra/terraform/` per [plan.md §9](./plan.md): root `main.tf`/`variables.tf`/`outputs.tf`, `environments/dev/`, and module stubs `modules/{network,rds,s3-audit,ecr,ecs-fargate,alb,iam}` with the minimum resources to `terraform validate` (full apply is out of scope for this branch — bodies can be one-resource stubs). The `s3-audit` module MUST configure Object Lock Compliance + SSE-KMS + Block-Public-Access. **Principle II**, **Principle III**, **Principle VI**.
- [ ] T057 CI hardening: wire gitleaks (secret-scan job in T005) against the full repo + history, OWASP dependency-check (Java) and `npm audit --omit=dev --audit-level=high` (Node) for the dep-scan job. All six required checks (T005) must run on every PR. **Principle V**, **Principle VI**.
- [ ] T058 [P] Latency benchmark harness at `backend/src/test/java/.../benchmarks/IngestionLatencyBenchmarkTest.java` (JUnit-driven, Testcontainers): POST 200 sequential good fixtures, assert 95p `202`-response under 500 ms (SC-001); poll Patient read, assert 95p availability under 2 s after acknowledgement (SC-002). Skipped in CI by default via a `@Tag("benchmark")` gate so it can be opted into without slowing PRs.
- [ ] T059 [P] Playwright end-to-end test at `frontend/e2e/quickstart.spec.ts` covering SC-008: assumes Compose is up; calls `./test-client/post-fixture.sh fixtures/adt-a01-good.hl7`, opens the Inspector, asserts the new message is present with status `PERSISTED` within the SC-008 envelope, clicks into the detail view, asserts raw HL7 and FHIR Patient/Encounter both rendered.
- [ ] T060 Documentation pass: top-level `README.md` (5-minute quickstart link, explicit "demo-only auth posture" callout per [plan.md §Summary](./plan.md)), root-level `CLAUDE.md` left intact, `docs/FUTURE.md` populated with the parked items from [plan.md §8](./plan.md) (additional message types, MLLP, FHIR `search`, RBAC, etc.). **Principle X** (Demo Scope is Defended).

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: T001 first; T002, T003, T004, T005 can run in parallel after T001.
- **Foundational (Phase 2)**: depends on Setup (T002 for backend deps). The five Flyway migrations are ordered (V1 → V2 → V3 → V4 → V5) — only T010 (V5) can run in parallel with later filters/observability since it touches a fresh file. Filters/observability (T011–T016) can be done in parallel once T002 is done.
- **User Story 1 (Phase 3)**: depends on Foundational being green. All US1 test tasks (T017–T027) come BEFORE the matching implementation tasks (T028–T041) and MUST be observed to fail. Tests in different files (T017–T023 are all [P]) can be written in parallel.
- **User Story 2 (Phase 4)**: backend pieces (T046–T048) depend on US1 because the Inspector reads the same `inbound_message`/`fhir_resource`/`validation_error` tables and reuses `MessageTransformationService`. Frontend pieces (T049–T053) depend only on T049's generated client + the backend contracts; they can be developed in parallel with the backend pieces against fixtures.
- **Polish (Phase 5)**: T054, T055, T058, T059 can run in parallel; T056 (Terraform), T057 (CI hardening), and T060 (docs) are mostly independent of one another.

### User Story Dependencies

- **US1**: depends on Foundational only.
- **US2**: depends on Foundational AND on US1's `MessageTransformationService` + `AuditEventEmitter` being in place (replay reuses them). US2 frontend can be scaffolded against the OpenAPI contract in parallel with US1 backend work.

### Tests-First Gates (Principle IV)

- US1 implementation (T028–T041) MUST NOT begin until T017–T027 are written and confirmed to fail.
- US2 replay implementation (T048) MUST NOT begin until T044 + T045 are written and confirmed to fail.
- Non-business-logic infrastructure (Flyway DDL, Spring Security plumbing, Logback wiring, Angular scaffolding) is exempt and is implemented directly.

### Parallel Opportunities

- All Setup [P] tasks (T003, T004) after T001.
- All US1 contract + unit test tasks (T017–T023) — different files.
- US1 mapper implementations T031, T032 — different files, after T031 entity model is done.
- US2 frontend [P] tasks (T049, T050) — different files.
- All Polish [P] tasks (T054, T055, T058, T059).

---

## Parallel Example: User Story 1 tests

```bash
# After Foundational checkpoint, write these in parallel — they MUST fail before T028–T041 land:
Task: "[US1] Contract test happy-path POST /ingest/hl7v2 → IngestionContractTest.java"          # T017
Task: "[US1] Contract test ingestion error paths → IngestionErrorContractTest.java"             # T018
Task: "[US1] Contract test GET /fhir/Patient/{id} → FhirPatientReadContractTest.java"           # T019
Task: "[US1] Contract test GET /fhir/Encounter/{id} → FhirEncounterReadContractTest.java"       # T020
Task: "[US1] Unit test ADT^A01 schema validator → Adt01SchemaValidatorTest.java"                # T021
Task: "[US1] Unit test PID → Patient mapper → PidToPatientMapperTest.java"                      # T022
Task: "[US1] Unit test PV1 → Encounter mapper → Pv1ToEncounterMapperTest.java"                  # T023
```

---

## Implementation Strategy

### MVP First (US1 only)

1. Phase 1 — Setup
2. Phase 2 — Foundational (gate)
3. Phase 3 — US1 tests then implementation
4. **STOP and demo**: a single curl + a single `GET /fhir/Patient/{id}` is the entire MVP narrative. SC-001, SC-002, SC-003, SC-004, SC-005, SC-006, SC-009 all become measurable here.

### Incremental Delivery

1. Setup + Foundational green → infrastructure ready
2. US1 green → ingestion + FHIR REST demo (MVP)
3. US2 green → Inspector demo (the portfolio money shot per [spec.md US2 §Why this priority](./spec.md))
4. Polish → ADRs in place, Terraform stub-validates, CI hardened, SC-008 onboarding measured

### Parallelization

With two engineers:

- Eng A: Phase 2 (Flyway + filters) → Phase 3 (US1)
- Eng B: scaffolds Angular + writes US1 contract tests (T017–T020) in parallel, then picks up US2 frontend (T049–T053) the moment the backend contracts (T046–T048) compile against the OpenAPI spec.

---

## Constitution Principle Coverage (audit map)

Every principle has at least one task that exercises it. This map is for reviewers checking that nothing slipped through.

| Principle | Covered by |
|---|---|
| I — PHI in logs | T014, T015, T018, T025 |
| II — PHI access auditing | T026, T035, T037, T038, T039, T040, T045, T047, T048, T056 |
| III — PHI encryption at rest + transit | T040, T041, T055 (ADR-0014), T056 |
| IV — Test-first for business logic | T017–T027, T042–T045 (gating T028–T041, T046–T048) |
| V — No merging on red | T005, T057 |
| VI — Secret hygiene | T011, T050, T056, T057 |
| VII — Observability from day one | T012, T015, T027, T036, T037, T050 |
| VIII — Idempotent ingestion | T008, T009, T024, T033, T034, T045, T048 |
| IX — Schema validation at boundaries | T011, T013, T016, T018, T021, T029, T030 |
| X — Demo scope is defended | T060 (`FUTURE.md`) plus the absence of MLLP/RBAC/additional-resource tasks |
| XI — ADRs for architectural decisions | T054, T055 |

---

## Notes

- `[P]` = different files, no dependencies on incomplete tasks in the same phase.
- `[US1]` / `[US2]` map every implementation task to a user story for traceability.
- Each task is sized to be reviewable and committable in one sitting. Where a natural grouping was larger (e.g., 4 JPA entities, 17 ADRs), it has been deliberately bundled rather than fragmented to keep the total within the demo-scope task budget.
- Tests for business logic MUST fail before the matching implementation begins (Principle IV). The phase ordering encodes this; CI required-checks (T005, T057) enforce it for code review.
- After each task is committed, the next task should be picked up directly — there are no implicit checkpoints between tasks within a phase beyond the explicit Checkpoints above.
