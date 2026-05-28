# HL7 v2 → FHIR R4 Interoperability Hub

A focused interoperability hub that accepts legacy **HL7 v2 ADT^A01** patient-admission
messages over HTTP, transforms them into modern **FHIR R4** `Patient` and `Encounter`
resources, persists them, and exposes them through a small FHIR REST API.

**The problem it solves.** Hospital systems still speak HL7 v2 — a pipe-and-hat
format from the 1980s — while modern clinical apps, analytics, and patient-facing
tooling expect FHIR R4 JSON over REST. This hub is the seam between the two worlds:
it ingests the legacy wire format, validates it at the boundary, maps it to FHIR,
and serves the result, with the audit trail, PHI-safe logging, and idempotency that
a healthcare data path demands.

> This is a **portfolio demonstration**, not a certified product. Scope is deliberately
> narrow (one message type, one transport, two FHIR resources) and the auth posture is
> demo-only. The engineering *discipline* — not breadth — is the point. See
> [How it was built](#how-it-was-built).

---

## Architecture

A message flows through five stages: **ingest → validate → transform → persist → FHIR read.**
Ingestion is a synchronous-persist / `202 Accepted` handshake — the raw body is durably
stored before the response returns, then transformation runs behind an in-process seam
(`InboundMessageProcessor`) that carries only the message id, never the PHI body, so it can
be swapped for a real queue later without an API change.

```mermaid
flowchart TD
    sender["HL7 v2 sender<br/>(curl / bundled test-client)"]
    sender -->|"POST /ingest/hl7v2<br/>Bearer token · application/hl7-v2"| filters

    subgraph backend["Spring Boot 4 backend (single JVM)"]
        filters["Filters:<br/>correlation-id · bearer auth · size + content-type cap"]
        ingest["IngestionController<br/>parse MSH · persist raw · return 202"]
        seam["InboundMessageProcessor<br/>(in-process seam — id only, no PHI)"]
        xform["MessageTransformationService"]
        validate["ADT^A01 schema validation<br/>(HAPI HL7 v2)"]
        map["PID → Patient · PV1 → Encounter<br/>(HAPI FHIR R4 models)"]
        read["FhirReadController<br/>GET /fhir/Patient/{id}<br/>GET /fhir/Encounter/{id}"]
        audit["AuditEventEmitter<br/>→ append-only JSONL sink"]

        filters --> ingest --> seam --> xform
        xform --> validate --> map
    end

    map --> db[("Postgres 16<br/>inbound_message · fhir_resource<br/>idempotency_key · validation_error")]
    db --> read
    read -->|"application/fhir+json"| client["FHIR R4 client"]
    xform -.->|"every PHI read/write"| audit
```

**Boundaries that matter:**

- **Auth + validation at the edge** — bearer token, body-size cap, and content-type
  check reject bad input before any business logic runs.
- **Idempotency at the database** — a unique constraint on `(sending_application, MSH-10)`
  plus an insert-or-fetch arbiter means replaying the same message creates zero duplicate
  Patient/Encounter resources.
- **One correlation id** threads every request → log line → span → audit event.
- **Audit sink is separate from the operational store** — append-only JSONL locally
  (S3 Object-Lock in the AWS design), distinct from Postgres.
- **FHIR persistence** is a single `fhir_resource` table holding HAPI-serialized JSONB,
  streamed straight back on read (no ORM-to-FHIR round-trip), with weak `ETag` /
  `Last-Modified`.

---

## Quickstart / demo

**Prerequisites:** Docker Desktop (or Docker Engine ≥ 24 with Compose v2). Nothing else —
the stack carries the JVM, Postgres, and LocalStack.

### 1. Bring up the stack

```bash
docker compose -f docker/docker-compose.yml up -d --build
docker compose -f docker/docker-compose.yml ps   # wait for `backend` = healthy
```

This starts Postgres, the backend (with the OpenTelemetry agent attached), LocalStack S3,
and an Angular dev-server container. The demo bearer token baked into Compose is
`dev-only-do-not-ship`.

### 2. Ingest an ADT^A01 message

**macOS / Linux** — use the bundled test client (set the token to match the stack first):

```bash
export FHIR_HUB_AUTH_TOKEN=dev-only-do-not-ship
./test-client/post-fixture.sh fixtures/adt-a01-good.hl7
```

**Windows (PowerShell)** — ⚠️ `curl.exe --data-binary @file.hl7` **mangles the carriage-return
segment terminators** that HL7 v2 requires, producing a spurious
`400 HL7_PARSE_MISSING_SEGMENT`. Read the file, normalize line endings to bare `CR`, and post
with `Invoke-WebRequest` instead:

```powershell
$token = 'dev-only-do-not-ship'   # matches FHIR_HUB_AUTH_TOKEN in docker-compose.yml

# HL7 v2 uses bare CR (`r) between segments. Convert CRLF/LF to CR before posting.
$body = (Get-Content -Raw test-client/fixtures/adt-a01-good.hl7) -replace "`r`n", "`r" -replace "`n", "`r"

Invoke-WebRequest -Uri http://localhost:8080/ingest/hl7v2 `
  -Method Post `
  -Headers @{ Authorization = "Bearer $token" } `
  -ContentType 'application/hl7-v2' `
  -Body $body |
  Select-Object -ExpandProperty Content
```

Either way you get `202 Accepted` with a JSON body: `messageId`, `status`, `receivedAtUtc`,
and `correlationId`.

### 3. Read the transformed FHIR resource

The hub mints a random FHIR logical id for each resource at transform time. The Inspector UI
that would surface those ids is [not built yet](#current-status), so for now look the id up in
Postgres:

```bash
docker compose -f docker/docker-compose.yml exec postgres \
  psql -U fhirhub -d fhirhub -c "select id, resource_type from fhir_resource;"
```

Then read it back as FHIR R4 JSON:

```bash
# macOS / Linux
curl -H "Authorization: Bearer dev-only-do-not-ship" \
  http://localhost:8080/fhir/Patient/<paste-patient-id>
```

```powershell
# Windows (PowerShell)
Invoke-WebRequest -Uri "http://localhost:8080/fhir/Patient/<paste-patient-id>" `
  -Headers @{ Authorization = "Bearer dev-only-do-not-ship" } |
  Select-Object -ExpandProperty Content
```

The response is a FHIR R4 `Patient` with the PID-derived name, identifier, birth date, and
sex, carrying `ETag` and `Last-Modified` headers. `GET /fhir/Encounter/{id}` works the same
way and its `subject` references the related Patient.

To exercise the failure path, POST `fixtures/adt-a01-missing-pid.hl7` and observe the
structured error envelope (a stable error code, no PHI, no stack trace).

### Tear down

```bash
docker compose -f docker/docker-compose.yml down -v
```

---

## How it was built

The differentiator here is **process**, not feature count. Every line of production code was
preceded by a specification, a plan, and (for business logic) a failing test — built with
**Spec-Driven Development** ([Spec Kit](https://github.com/github/spec-kit)).

**A project constitution governs the work.** [`.specify/memory/constitution.md`](.specify/memory/constitution.md)
ratifies 11 principles that every change is reviewed against:

| # | Principle | In one line |
|---|---|---|
| I | PHI Confidentiality in Logs *(non-negotiable)* | No PHI in logs, traces, or stdout — redacted at the emission boundary. |
| II | PHI Access Auditing *(non-negotiable)* | Every PHI read/write emits an audit event to an append-only sink. |
| III | PHI Encryption at Rest & in Transit *(non-negotiable)* | TLS 1.2+ in transit, encryption at rest; synthetic test data only. |
| IV | Test-First for Business Logic | HL7 parse, FHIR mapping, idempotency, audit get failing tests first. |
| V | No Merging on Red | No merge while any required CI check fails; no `--no-verify`. |
| VI | Secret Hygiene | Secrets from env/secret-manager only — never in source or history. |
| VII | Observability from Day One | Structured logs, metrics, traces, one correlation id, from the first merge. |
| VIII | Idempotent Ingestion | Replay is safe — idempotency enforced at the persistence layer. |
| IX | Schema Validation at Boundaries | Every external input validated against an explicit schema first. |
| X | Demo Scope is Defended | One message type, one transport, two resources — expansion needs an ADR. |
| XI | ADRs for Architectural Decisions | Significant choices recorded under `docs/adr/`, one file each. |

**The artifact trail** for the implemented feature lives under
[`specs/001-adt-a01-ingestion-inspection/`](specs/001-adt-a01-ingestion-inspection/):

- [`spec.md`](specs/001-adt-a01-ingestion-inspection/spec.md) — user stories, acceptance
  scenarios, edge cases, and measurable success criteria (SC-001…SC-009).
- [`plan.md`](specs/001-adt-a01-ingestion-inspection/plan.md) — architecture, data model,
  API contracts, observability plan, and a per-principle Constitution Check.
- [`tasks.md`](specs/001-adt-a01-ingestion-inspection/tasks.md) — dependency-ordered tasks,
  with tests-first gates called out per Principle IV.
- [`contracts/`](specs/001-adt-a01-ingestion-inspection/contracts/) — OpenAPI definitions,
  the audit-event JSON schema, and the enumerated error codes that the tests drive from.

**Tests-first in practice.** Business-logic tests (the ADT^A01 validator, the PID→Patient and
PV1→Encounter mappers, idempotency, PHI-redaction, audit emission, correlation-id propagation)
were written to fail before the implementation landed, and run against a real Postgres via
Testcontainers.

---

## Tech stack

| Layer | Choice |
|---|---|
| Language / runtime | Java 21 |
| Framework | Spring Boot 4.0, Spring Security, Spring Data JPA |
| HL7 v2 parsing | HAPI HL7 v2 (`hapi-base` + `hapi-structures-v25`, v2.5.1) |
| FHIR R4 | HAPI FHIR R4 model classes (`hapi-fhir-structures-r4`) |
| Persistence | Postgres 16, Flyway migrations, JSONB resource storage |
| Observability | OpenTelemetry (Java agent + SDK), structured JSON logs (Logback) |
| Audit sink | Append-only JSONL file (local); S3 Object-Lock in the AWS design |
| Build / quality | Gradle (Kotlin DSL), Spotless + Checkstyle |
| Tests | JUnit 5, Mockito, Spring Boot Test, Testcontainers, REST-assured |
| Local stack | Docker Compose (Postgres + backend + LocalStack S3 + Angular dev server) |
| Frontend (planned) | Angular 18 (Inspector SPA — scaffold only so far) |
| Cloud (designed, not provisioned) | ECS Fargate + RDS + S3, via Terraform |

---

## Current status

**MVP is working: User Story 1 — ingestion + FHIR read.** End-to-end you can POST an
ADT^A01 message, get a `202`, and read back the transformed FHIR `Patient` and `Encounter`.
This path is **idempotent** (replays create no duplicates), **PHI-safe in logs** (verified by
an integration test that greps captured log/span output against a curated PHI-token list), and
runs behind **bearer auth**, a **boundary-validation** layer, and **end-to-end correlation
ids**. Audit emission to the append-only file sink is implemented and covered by tests.

**Honestly deferred — not yet built:**

- **Inspector UI (User Story 2).** No backend list/detail/replay endpoints; the frontend is an
  Angular *scaffold* with no Inspector components. The Compose `frontend` container serves the
  default Angular app, not a working Inspector.
- **S3 audit sink.** Only the local `FileAuditSink` exists; `S3AuditSink` (LocalStack/AWS) is
  designed but unimplemented.
- **WebTestClient test migration.** Seven `GET`-based contract tests are `@Disabled`: REST-assured
  5.5.0 throws an NPE on `GET` under JDK 21 + Spring Boot 4 and needs porting to `WebTestClient`.
- **Terraform / AWS deploy, ADR write-ups, and CI hardening** (benchmark + Playwright e2e) are
  planned in `tasks.md` but not yet implemented (`docs/adr/` and `infra/terraform/` are
  placeholders).

**Known open issues** are tracked in [`docs/FUTURE.md`](docs/FUTURE.md) — notably that the
audit JSONL is not being written inside the running Docker stack (the code path and its tests
pass; the file-sink wiring in the container is under investigation), and the
`curl`-on-Windows CRLF gotcha documented in the quickstart above.

---

*Synthetic data only. No real PHI is committed to this repository or run through any
environment. The static bearer token is demo-only and would be replaced by OAuth/OIDC in
production.*
