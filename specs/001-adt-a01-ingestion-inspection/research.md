# Phase 0 — Research and Decision Log

**Feature**: ADT^A01 Ingestion and Inspection
**Date**: 2026-05-20

This document records the resolutions of every NEEDS-CLARIFICATION item that surfaced while drafting [plan.md](./plan.md), together with the reasoning behind decisions the plan inherits but does not re-derive. Each entry follows: **Decision · Rationale · Alternatives considered**.

## 1. HL7 v2 parser

- **Decision**: HAPI HL7 v2 (`ca.uhn.hapi:hapi-base` + `ca.uhn.hapi:hapi-structures-v25`), wired through HAPI's `PipeParser` for the canonical v2.5/v2.5.1 wire format.
- **Rationale**: HAPI is the de-facto Java reference parser, with structures shipped per HL7 version. Its `MessageValidator` lets us enforce ADT^A01 segment/field minimums declaratively, which is what Principle IX requires at the message boundary. The pre-decided tech stack pins this choice, and this entry records *why* it is defensible: alternatives carry meaningful integration cost we don't need to absorb.
- **Alternatives considered**: hand-rolled MSH/PID/PV1 parser (rejected — recreates the bug surface HAPI has already shaken out); NextGen Healthcare's parser (commercial; unnecessary for a synthetic-data demo); regex-only validation (rejected — Principle IX requires explicit schema validation, not pattern-matching).
- **Will be captured in**: ADR-0001.

## 2. FHIR model and persistence

- **Decision**: HAPI FHIR R4 model classes only (`ca.uhn.hapi.fhir:hapi-fhir-structures-r4`), serialized with HAPI's `IParser` into JSONB columns of a single `fhir_resource` table. Custom Spring controllers expose the FHIR `read` interaction. HAPI's bundled JPA FHIR server is explicitly NOT used.
- **Rationale**: We need wire-format-correct FHIR JSON on the way out and HAPI's parser-roundtrip guarantees that without an ORM-to-FHIR layer. A single resource table fits the in-scope cardinality (two resource types, Principle X) and keeps the next resource (e.g., Observation) a row-shape addition rather than a schema migration. The pre-decided stack rules out HAPI's JPA server; this entry captures the consequent reasoning.
- **Alternatives considered**: dedicated `patient` and `encounter` tables with mapped columns (rejected — duplicates FHIR's own model, doesn't scale to a third resource type, and forces a parallel column-vs-FHIR-JSON serialization story); HAPI JPA server (excluded by pre-decided stack — and would force us to live with HAPI's schema migrations, which we don't control); event-sourced FHIR store (overkill for a demo).
- **Will be captured in**: ADR-0002, ADR-0013.

## 3. Database engine

- **Decision**: Postgres 16. Local via Docker Compose, AWS via RDS `db.t4g.micro` for the demo footprint.
- **Rationale**: JSONB support is first-class (needed for §2's persistence approach), unique-constraint enforcement is robust (needed for idempotency, Principle VIII), and Postgres is widely covered by Testcontainers (Principle IV / test-first). RDS Postgres is in the AWS free tier for 12 months on `db.t4g.micro`, which keeps the demo's running cost under hobby budget.
- **Alternatives considered**: MySQL/MariaDB (rejected — weaker JSONB story); DynamoDB (rejected — the FHIR resource model wants per-resource-id strong-read consistency and indexed business-identifier uniqueness, both awkward in Dynamo); SQLite (rejected — no realistic AWS deployment story, no concurrent-writer story).
- **Will be captured in**: ADR-0003.

## 4. Frontend framework

- **Decision**: Angular 18 (TypeScript). Served by the Spring Boot backend as static resources at the same origin.
- **Rationale**: Pre-decided in the tech stack. Same-origin hosting removes the CORS surface from the demo and keeps the bearer-token model trivial — the SPA reuses the same `Authorization` header that the test client uses. Angular 18 has stable standalone components and a good CLI story for a single-developer demo.
- **Alternatives considered**: React (rejected — pre-decided stack chose Angular); Vue (same).
- **Will be captured in**: ADR-0004.

## 5. Backend framework and language

- **Decision**: Spring Boot 4.0.x on Java 21. Build with Gradle (Kotlin DSL) under `backend/`.
- **Rationale**: Pre-decided. Spring Boot 4 brings Spring Framework 7, virtual-thread-friendly servlets via Tomcat 11, and the OpenTelemetry agent's autoconfiguration support. Java 21 LTS gives us virtual threads, pattern matching for switch, and records — all of which keep the transformation/validation code readable.
- **Will be captured in**: ADR-0005.

## 6. IaC and local container packaging

- **Decision**: Terraform (HCL) for AWS infra under `infra/terraform/`; Docker Compose for local stack under `docker/`.
- **Rationale**: Pre-decided. Terraform's state-file model and module ecosystem fit the limited topology (RDS, ECS, S3, ALB, ECR, IAM). Compose mirrors the topology one-to-one for local dev — symmetry between local and AWS is the design intent (§9 of the plan).
- **Alternatives considered**: CDK (rejected — adds a language tax with no compensating benefit at this scale); SAM / Serverless (rejected — assumes Lambda, but the seam decision keeps us on a single JVM); Kustomize/Helm (rejected — we have no Kubernetes target in scope).
- **Will be captured in**: ADR-0006.

## 7. Observability stack

- **Decision**: OpenTelemetry Java agent for auto-instrumentation, SDK for manual spans on business boundaries. ADOT collector as ECS sidecar in AWS; OTLP-to-console exporter locally. CloudWatch Logs/Metrics + AWS X-Ray as backends.
- **Rationale**: Pre-decided stack pins OpenTelemetry. ADOT is the AWS-supported distribution and accepts OTLP from the agent unchanged. Same configuration runs locally with a console exporter, which keeps the dev loop tight without needing a full observability backend on a laptop.
- **Alternatives considered**: Spring Boot Actuator + Micrometer alone (rejected — Principle VII calls out distributed traces specifically, and Micrometer doesn't ship spans); Prometheus + Tempo + Loki locally (overkill for a 5-minute-onboarding constraint); New Relic / Datadog agents (commercial; unnecessary for a demo).
- **Will be captured in**: ADR-0007.

## 8. Audit sink — S3 Object Lock posture

- **Decision**: S3 bucket with Object Lock in **Compliance** retention mode, default 7-year retention, SSE-KMS encryption, versioning enabled. Locally, an append-only JSONL file at `var/audit/audit.log`. LocalStack S3 is wired up in `docker-compose.yml` for contributors who want to exercise the S3 path locally.
- **Rationale**: Compliance mode means even the root account cannot shorten retention or delete an object before it expires — the strongest append-only guarantee S3 offers. This is the right posture for a *demonstration* of healthcare audit hygiene (Principle II) even though the data is synthetic. 7 years approximates HIPAA-style retention without being so long that bucket-versioning cost becomes a teaching distraction.
- **Alternatives considered**: Governance mode (rejected — allows privileged delete, defeats the demo's point); EFS append-only mount (rejected — no Object-Lock equivalent); a dedicated WORM service like Glacier Vault Lock (rejected — adds complexity for the same demo guarantee S3 already provides); writing audit events to a separate Postgres schema (rejected — Principle II requires a sink distinct from the operational store, and Postgres is already the operational store).
- **Will be captured in**: ADR-0008.

## 9. Processing model and seam

- **Decision**: Ingestion controller persists the raw HL7 body synchronously, returns 202, and hands the message ID to `InboundMessageProcessor#enqueue`. Today's implementation is a Spring `ThreadPoolTaskExecutor` in the same JVM; the interface is designed so SQS can drop in later without API change. SQS is *not* wired up in this feature.
- **Rationale**: Pre-decided. The seam exists because a real production system would not run transformation in the request thread, but standing up SQS for a demo adds infrastructure cost (real or LocalStack) without engineering signal. Designing the interface explicitly *now* is the engineering-thinking story; deferring SQS is the scope-discipline story (Principle X).
- **Seam invariants** (recorded so future implementers can't break them silently):
  1. The 202 is returned only after `inbound_message` is durably persisted.
  2. The seam carries only the message ID, never the HL7 payload. This is the property that lets a real queue drop in without payload-size or PHI-in-queue concerns.
  3. The processor invocation is non-blocking from the controller's perspective.
  4. Correlation-ID propagation across the seam is the implementation's responsibility, not the controller's.
- **Alternatives considered**: synchronous transform-then-respond (rejected — busts SC-001 under any non-trivial transformation load); embedded message queue like H2-MQ or in-memory ActiveMQ Artemis (rejected — adds a dependency that doesn't earn its weight for a demo); Kafka (rejected — explicit scope expansion per Principle X).
- **Will be captured in**: ADR-0009.

## 10. Authentication posture

- **Decision**: Static shared bearer token, loaded from `FHIR_HUB_AUTH_TOKEN` env var. Enforced by a Spring Security filter on `/ingest/**`, `/fhir/**`, and `/inspector/**`. Audit actor is the hardcoded string `demo-operator`. README is required to call out that this is demo-only and would be replaced by OAuth/OIDC in production.
- **Rationale**: Pre-decided. The point of this feature is the HL7→FHIR mapping and the audit/observability discipline, not yet-another-OAuth-integration. Capturing the demo-only posture in an ADR documents the trade-off so a reviewer doesn't read it as a security oversight.
- **Alternatives considered**: full OIDC against a hosted IdP (out of scope per Principle X — would need an ADR for the scope expansion); mTLS (rejected — same scope reasoning, plus cert distribution overhead); no auth at all (rejected — would force every observability and audit test to handle the no-actor case differently from the eventual auth'd case).
- **Will be captured in**: ADR-0010, ADR-0017.

## 11. AWS compute target

- **Decision**: ECS Fargate, single task, behind an Application Load Balancer. ECR for image storage. The Inspector SPA is served as static resources by the same backend task.
- **Rationale**: Fargate keeps the local-and-cloud topology symmetric (Docker on a laptop ↔ container task in AWS), no host patching, no autoscaling-group complexity. Cost is acceptable (~$10–15/mo at 0.25 vCPU / 0.5 GB always-on, less if powered down between demos). Free-tier RDS keeps total demo running cost under typical hobby budgets.
- **Alternatives considered**: EC2 (cheaper at long-run, but introduces AMI / patching / scaling surface that isn't part of the engineering story we want to tell); App Runner (simpler but doesn't slot cleanly into the RDS-private VPC, costs more per CPU-hour); ECS on EC2 (worst of both worlds for a single task).
- **Will be captured in**: ADR-0015.

## 12. HTTP media type for HL7 ingestion

- **Decision**: Custom media type `application/hl7-v2` on the `POST /ingest/hl7v2` endpoint. Charset is UTF-8, declared via `;charset=utf-8` when present.
- **Rationale**: There is no IANA-registered media type for raw HL7 v2 messages. `text/plain` would lose the semantic that the body is HL7, and `application/octet-stream` would lose the UTF-8 semantic. A vendor-prefixed `application/vnd.hl7v2` would also work but `application/hl7-v2` is more readable in docs and in the OpenAPI contract.
- **Alternatives considered**: `text/plain` (rejected — sender intent is hidden); `application/octet-stream` (rejected — charset confusion); a JSON envelope wrapping the HL7 string (rejected — adds a parse step before the existing parse step, and the spec describes raw HL7 over HTTP).
- **Will be captured in**: ADR-0012.

## 13. Idempotency key composition

- **Decision**: The idempotency key is the tuple `(MSH-3 sending application, MSH-10 message control ID)`, enforced by a unique constraint on `idempotency_key (sending_application, msh10_control_id)`.
- **Rationale**: The spec's assumption fixes this: MSH-10 is per-sender-unique by HL7 convention, so including MSH-3 makes the key globally unique without coordinating across senders. Enforcement at the DB layer (Principle VIII) handles concurrent POSTs of the same message correctly — the second insert raises a unique-constraint violation and the application converts it into an idempotent "you already sent this" response without creating duplicate FHIR resources.
- **Alternatives considered**: hash of the raw body (rejected — semantically wrong; a sender re-issuing the same logical message with a typo fix would get a new key and create duplicates); MSH-10 alone (rejected — collision across senders is plausible enough that the spec calls out the multi-sender scenario as an edge case).

## 14. Replay semantics

- **Decision**: Replay re-runs validation and transformation against the persisted `raw_hl7` body. The idempotency key is unchanged across replays, so the existing Patient and Encounter rows are reused (upsert with no-op when content is unchanged). A separate audit event with `operation: "replay"` is emitted whether the replay succeeds or fails.
- **Rationale**: Spec scenarios 3 and 4 of User Story 2 require this exact behavior — replaying a `FAILED` message after the upstream fix changes its status to `PERSISTED`, and replaying a `PERSISTED` message is a no-op on FHIR data but still emits an audit row. The persisted `raw_hl7` is the source of truth; replay never re-reads from the original sender.
- **Alternatives considered**: replay reuses the *parsed* state (rejected — the parsed state is what failed; reusing it defeats the point of "the operator fixed the underlying issue"); replay creates a new `inbound_message` row (rejected — would multiply audit history and confuse the Inspector list view).

## 15. PHI handling in error responses

- **Decision**: The error envelope contains `code`, `message`, `location` (optional), `correlationId`. The `message` is summary-safe text drawn from a fixed allow-list of error codes. `location` names a segment/field index only, never a value. FHIR endpoints wrap the same fields inside a FHIR `OperationOutcome`.
- **Rationale**: FR-004 and Principle I forbid PHI in error responses. A stable, code-driven envelope is the only design that holds up under code review without per-endpoint custom logic. The `correlationId` echo is what lets an operator turn a failure into an Inspector lookup without needing to share message content out-of-band.
- **Alternatives considered**: structured field-by-field error reports including offending values (rejected — leaks PHI on the failure path); HTTP status code alone (rejected — operators need codes for replay decisions and Inspector linking); RFC 7807 problem+json (considered — semantically compatible; rejected only because the `OperationOutcome` requirement on FHIR endpoints means we already have a second shape, and using a third for non-FHIR endpoints adds confusion).

## 16. Correlation ID strategy

- **Decision**: Read `X-Correlation-Id` from incoming requests; if absent or malformed, generate a UUID v4. Echo on the response. Store on `inbound_message.correlation_id`. Pipe into Logback MDC. Stamp as `correlation.id` attribute on every OTel span. Stamp on every audit event. Propagate across the in-process seam by copying MDC into the worker thread.
- **Rationale**: SC-009 requires the same ID to flow end-to-end across 100% of sample messages. A single header is simpler than reusing the W3C `traceparent` for this purpose (traceparent encodes sampling and parent-span semantics that don't match a business correlation ID; we use both, with different roles).
- **Alternatives considered**: W3C `traceparent` only (rejected — encodes too much; can't be hand-typed by a sender; sampling can drop it); per-endpoint correlation conventions (rejected — defeats the "one ID end-to-end" property).
- **Will be captured in**: ADR-0016.

## 17. PHI-at-rest encryption

- **Decision**: RDS storage encryption (KMS-managed key) for the database volume; SSE-KMS on the S3 audit bucket. No column-level encryption of `raw_hl7` or of any column in `fhir_resource.content_json`.
- **Rationale**: Principle III's letter says "either database-level encryption or column-level encryption for sensitive fields." RDS volume encryption satisfies the letter; the spirit (defense-in-depth in the event a backup leaks) is weaker than column-level. For a synthetic-data demo with no real PHI, the cost (key-rotation flow, sealed envelopes, queryability loss on the inserts) does not earn its weight. ADR-0014 will record the trade-off so the deviation from the strict reading is visible.
- **Alternatives considered**: column-level encryption via Spring Data + AWS Encryption SDK (rejected for this feature — recordable trade-off, not a silent gap); pgcrypto symmetric encryption (rejected — same trade-off plus key-management complications).
- **Will be captured in**: ADR-0014.

## 18. Test posture

- **Decision**: Unit tests with JUnit 5 + Mockito; integration tests with Spring Boot Test + Testcontainers Postgres; HTTP contract tests with REST-assured driven by the OpenAPI files under `contracts/`; Angular unit tests with Karma/Jasmine; an end-to-end happy-path test that boots the full Compose stack and asserts SC-001 (latency) and SC-009 (correlation propagation).
- **Rationale**: Principle IV makes test-first mandatory for the HL7, FHIR, validation, idempotency, and audit modules. Testcontainers ensures the integration tests hit a real Postgres rather than an H2-faked-Postgres (the constitution's anti-mock guidance, in spirit). Contract tests against the OpenAPI files turn the documented API surface into an enforceable schema.
- **Alternatives considered**: in-memory H2 (rejected — JSONB semantics and unique-constraint behavior diverge enough to mask bugs); WireMock for FHIR (rejected — we control the FHIR endpoints, there's nothing to stub); only end-to-end testing (rejected — would skip the test-first cycle Principle IV requires for transformation).

## 19. CI required-checks list

- **Decision**: GitHub Actions workflow that runs, on PR and on push to `main`: Gradle build + unit tests; Gradle integration tests (Testcontainers); Spotless / Checkstyle; Angular `npm run lint` + `npm run test -- --watch=false`; gitleaks; OWASP Dependency-Check or `dependabot` security advisories (whichever lands first); a structural-OpenAPI lint against `contracts/`.
- **Rationale**: Principle V says no merging on red. This list is what "red" means in this repo. Branch protection on `main` requires all of them.
- **Will be captured in**: ADR-0011.

## 20. Out-of-scope items parked

The following surface in the spec or come up naturally but are *not* in this plan. They live in `docs/FUTURE.md` and require an ADR before any of them can move into a future plan (Principle X):

- ADT^A02, A03, A04, A08 and other HL7 message types
- MLLP/TCP, file drop, SFTP transports
- FHIR resources beyond Patient and Encounter
- FHIR `search`, `create`, `update`, `delete` interactions
- FHIR XML wire format
- Real OAuth/OIDC, RBAC, multi-tenancy
- A real queue (SQS, Kafka, Kinesis) behind the `InboundMessageProcessor` seam
- Automated audit-retention purge / lifecycle policies
- Column-level PHI encryption (recorded as a known gap; ADR-0014)
- Production-grade rate limiting and DDoS protection beyond the size cap

No NEEDS-CLARIFICATION items remain open. Phase 1 may proceed.
