# Feature Specification: ADT^A01 Ingestion and Inspection

**Feature Branch**: `001-adt-a01-ingestion-inspection`
**Created**: 2026-05-20
**Status**: Draft
**Input**: User description: "ADT^A01 Ingestion and Inspection — accept HL7 v2 ADT^A01 admission messages over HTTP, transform to FHIR Patient + Encounter, expose via FHIR REST API, and provide an Inspector UI for listing messages, drilling into raw HL7 vs. transformed FHIR side-by-side, and replaying failed messages."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - HL7 ADT^A01 Ingestion (Priority: P1)

A legacy hospital system POSTs an HL7 v2 ADT^A01 patient-admission message to the hub and receives an immediate acknowledgement, so the sender can confirm the hub accepted the message without blocking its own workflow. The hub validates the message, transforms it into FHIR Patient and Encounter resources, persists those resources, and makes them queryable by the FHIR REST API.

**Why this priority**: This is the primary data path — without ingestion, nothing else in the system has a reason to exist. The 202-then-process pattern lets senders integrate against the hub without coupling their availability to ours, which is the standard interoperability contract.

**Independent Test**: Can be fully tested by POSTing a known-good ADT^A01 fixture, asserting a 202 response, polling the FHIR REST API for the resulting Patient resource, and verifying its fields match the fixture. Delivers a measurable end-to-end ingestion path even without the Inspector UI.

**Acceptance Scenarios**:

1. **Given** a syntactically well-formed ADT^A01 message containing MSH, PID, and PV1 segments with all minimum required fields, **When** the sender POSTs it to the ingestion endpoint, **Then** the hub MUST respond `202 Accepted` within 500 ms, return a stable message identifier in the response body, and queue the message for processing.
2. **Given** an accepted message, **When** the sender retrieves the corresponding Patient resource via the FHIR REST API by ID, **Then** the response MUST be `200 OK` within 2 seconds of acknowledgement and the Patient resource MUST reflect the PID segment fields (name, identifiers, date of birth, sex).
3. **Given** an accepted message, **When** the sender retrieves the corresponding Encounter resource via the FHIR REST API by ID, **Then** the Encounter resource MUST reflect the PV1 segment fields (admission type, class, location, attending provider reference).
4. **Given** a message that fails schema validation (e.g., missing PID, malformed encoding, missing required field), **When** the sender POSTs it, **Then** the hub MUST respond with a structured error containing a stable error code identifying the failure, the response body MUST NOT contain PHI or internal stack traces, and the failed message MUST be recorded with its raw body and failure reason for later inspection.
5. **Given** a message that has already been successfully ingested (same MSH-10 control ID and sending application), **When** the same message is POSTed again, **Then** the hub MUST accept it and complete processing without creating duplicate Patient or Encounter resources; the existing resources MUST remain unchanged or be updated only if the replayed message represents a corrected version.

---

### User Story 2 - Inspector UI for Operators (Priority: P1)

An interop operator opens the Inspector web UI to monitor ingestion. The operator sees a list of recently ingested messages with their processing status (received, transformed, persisted, failed), opens a message to see the raw HL7 alongside the resulting FHIR resources side-by-side, and — for failed messages — triggers a replay from the UI to attempt re-processing after the underlying issue has been fixed.

**Why this priority**: Without operator visibility, ingestion failures are invisible until a downstream consumer notices missing data. The Inspector is the supportability surface that makes a portfolio-grade demo defensible: it shows the engineering thinking around troubleshooting, replay, and the HL7-to-FHIR transformation that's the core of the project.

**Independent Test**: Can be fully tested by ingesting a known-good message and a known-bad message via Story 1's path, then verifying the Inspector lists both with correct statuses, that the detail view for the good message shows aligned raw HL7 and FHIR JSON, that the detail view for the bad message shows the validation error, and that replaying the bad message after the upstream fix changes its status to "persisted" without creating duplicate resources.

**Acceptance Scenarios**:

1. **Given** one or more messages have been POSTed to the ingestion endpoint, **When** the operator loads the Inspector list view, **Then** each message MUST be displayed with at minimum: a hub-assigned message identifier, the MSH-10 control ID, the sending application (MSH-3), the received-at UTC timestamp, and the current processing status; the list MUST be ordered by received-at descending by default.
2. **Given** the operator selects a message from the list, **When** the detail view opens, **Then** the raw HL7 body MUST be displayed alongside the transformed FHIR Patient and Encounter resources in a side-by-side layout, and the source segment for each FHIR field SHOULD be visually traceable to the operator.
3. **Given** a message in `failed` status, **When** the operator clicks "Replay", **Then** the hub MUST re-execute validation and transformation against the persisted raw HL7 body, update the message's processing status, and refresh the UI to reflect the new outcome — without creating duplicate Patient or Encounter resources if the replay succeeds.
4. **Given** a message in `persisted` status, **When** the operator clicks "Replay", **Then** the hub MUST process the replay idempotently, leaving Patient and Encounter resource counts unchanged and recording a separate audit event for the operator-initiated replay action.
5. **Given** any Inspector action that reads or replays a message, **When** the action completes, **Then** an audit event MUST be written capturing the operator identity, UTC timestamp, the message identifier, the operation performed, and the outcome.

---

### Edge Cases

- **Missing required segment**: ADT^A01 message lacks MSH, PID, or PV1 — rejected at validation boundary with a structured error naming the missing segment; raw body persisted to the failed-messages store for inspection.
- **Malformed HL7 framing**: invalid segment terminator, wrong field separator, truncated body — rejected with a parse-failure error code; no partial state persisted.
- **Duplicate MSH-10 within the same sender**: treated as a replay (idempotent); no duplicate FHIR resources created.
- **Same MSH-10 from different sending applications**: treated as distinct messages; idempotency key includes sender identity.
- **Concurrent POSTs of the same message**: only one resulting Patient/Encounter pair exists after both requests settle, regardless of which arrived first.
- **Transformation succeeds but persistence fails**: message status reflects the failure; the operator can retry via Replay once the persistence issue is resolved.
- **Replay of a message that never existed**: operator UI returns a clear "not found" response; no side effects.
- **Oversized message body**: rejected at the HTTP boundary before parsing with a size-limit error; no PHI logged.
- **Message contains characters outside the declared encoding**: rejected at the validation boundary with an encoding-error code.
- **Patient identifier collision**: two distinct messages map (via PID-3) to the same patient identifier — treated as the same Patient resource per FHIR identifier semantics; Encounter is created as a new resource referencing that Patient.
- **FHIR REST API request for a resource that has not yet finished processing**: returns `404 Not Found` until the resource is persisted; the 2-second SLA bounds how long this state is visible.

## Requirements *(mandatory)*

### Functional Requirements

**Ingestion**

- **FR-001**: System MUST expose an HTTP POST endpoint that accepts HL7 v2 ADT^A01 messages as the request body.
- **FR-002**: System MUST respond to a valid ADT^A01 POST with `202 Accepted` and a hub-assigned message identifier within 500 ms at the 95th percentile under demo-scale load.
- **FR-003**: System MUST validate every incoming message against an explicit HL7 v2.5 (or v2.5.1) schema requiring MSH, PID, and PV1 segments and the minimum required fields within each before any business logic executes (constitution Principle IX).
- **FR-004**: System MUST reject messages that fail schema validation with a structured error response containing a stable error code; the response body MUST NOT contain PHI, internal state, or stack traces.
- **FR-005**: System MUST persist the raw HL7 body of every received message — valid or invalid — together with its processing status and any validation/transformation errors.
- **FR-006**: System MUST transform every successfully validated ADT^A01 message into FHIR R4 `Patient` and `Encounter` resources.
- **FR-007**: System MUST persist transformed FHIR resources such that they are queryable via the FHIR REST API within 2 seconds of the ingestion acknowledgement.
- **FR-008**: System MUST enforce ingestion idempotency at the persistence layer such that replaying a message with the same idempotency key (MSH-10 control ID combined with the sending application from MSH-3) produces zero duplicate Patient or Encounter resources (constitution Principle VIII).

**FHIR Read API**

- **FR-009**: System MUST expose a FHIR R4-compliant REST endpoint supporting `read` (GET by ID) for the `Patient` and `Encounter` resource types.
- **FR-010**: FHIR REST responses MUST conform to FHIR R4 wire format and content negotiation conventions for the resource types in scope.

**Inspector UI**

- **FR-011**: Operators MUST be able to view a paginated list of all ingested messages — successful and failed — via a web-based Inspector UI, including at minimum: hub-assigned message identifier, MSH-10 control ID, MSH-3 sending application, received-at UTC timestamp, and processing status.
- **FR-012**: The Inspector message list MUST be filterable by processing status (received, transformed, persisted, failed) and searchable by MSH-10 control ID.
- **FR-013**: The Inspector MUST provide a detail view that displays the raw HL7 body and the transformed FHIR Patient and Encounter resources side-by-side for any selected message.
- **FR-014**: The Inspector detail view MUST display structured validation or transformation errors when present, including the error code and the location within the source message where applicable.
- **FR-015**: Operators MUST be able to trigger a replay of any persisted message from the Inspector detail view; replays MUST re-execute validation and transformation against the persisted raw HL7 body and MUST be idempotent (FR-008).

**Audit, Logging, and Observability (constitution-required)**

- **FR-016**: System MUST emit an audit event for every read or write of a PHI-bearing resource (inbound messages, Patient, Encounter, audit records themselves), capturing actor identity, UTC ISO-8601 timestamp, resource type and identifier, operation, and outcome (constitution Principle II).
- **FR-017**: System MUST write audit events to an append-only sink distinct from the operational data store (constitution Principle II).
- **FR-018**: System MUST NOT emit PHI — patient names, identifiers, dates of birth, addresses, raw HL7 message bodies, or any reasonably identifying field — into application logs, error traces, telemetry attributes, stdout, or stderr (constitution Principle I).
- **FR-019**: System MUST attach a single correlation identifier to every HTTP request, ingested message, transformation step, persistence operation, and audit event so that the end-to-end path is traceable in observability tooling (constitution Principle VII).

**Test Client**

- **FR-020**: A test client included in the repository MUST be capable of POSTing canonical ADT^A01 fixtures (valid and invalid) to the ingestion endpoint and reporting hub responses, so that contributors can exercise the full path end-to-end without external tools.

### Key Entities *(include if feature involves data)*

- **Inbound Message**: A record of one HL7 v2 ADT^A01 submission. Attributes: hub-assigned identifier, raw HL7 body, MSH-10 control ID, MSH-3 sending application, received-at UTC timestamp, current processing status (`received`, `transformed`, `persisted`, `failed`), last error code and location (if any), correlation ID, links to derived FHIR resources (if any).
- **Patient**: A FHIR R4 Patient resource derived from the PID segment. Identified by a stable identifier carried in PID-3. Persisted such that replays of the same source message do not produce duplicates.
- **Encounter**: A FHIR R4 Encounter resource derived from the PV1 segment. References the corresponding Patient. Persisted such that replays of the same source message do not produce duplicates.
- **Validation Error**: A structured failure record. Attributes: stable error code, location within the source (segment.field), human-readable summary safe to display, association to the Inbound Message it belongs to.
- **Audit Event**: An append-only record of every PHI-touching operation. Attributes: actor identity, UTC ISO-8601 timestamp, resource type and identifier, operation (read/write/replay), outcome (success/failure), correlation ID. Distinct sink from the operational data store.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 95% of valid ADT^A01 POSTs receive a `202 Accepted` response in under 500 ms (per the user-specified latency target).
- **SC-002**: 95% of accepted ADT^A01 messages are retrievable as FHIR Patient and Encounter resources via the FHIR REST API within 2 seconds of acknowledgement.
- **SC-003**: Replaying the same ADT^A01 message any number of times produces exactly one Patient resource and exactly one Encounter resource — zero duplicates across 100% of replay test cases (per Principle VIII).
- **SC-004**: 100% of malformed ADT^A01 fixtures (missing MSH/PID/PV1, malformed framing, encoding errors) are rejected with a structured error response that contains zero PHI tokens.
- **SC-005**: 100% of representative ADT^A01 messages produce application log output that contains zero PHI tokens, verified by automated test against a curated PHI-token list (per Principle I).
- **SC-006**: 100% of PHI-bearing read, write, and replay operations produce a matching audit event in the append-only audit sink (per Principle II).
- **SC-007**: An operator can locate a specific failed message in the Inspector list and successfully trigger a replay in under 60 seconds, without leaving the UI.
- **SC-008**: A new contributor can clone the repository, run the included test client, ingest a fixture, and view the resulting message in the Inspector within 5 minutes from a clean clone.
- **SC-009**: The single correlation identifier from an ingestion POST is present in every downstream log line, span, and audit event associated with that message — verifiable by end-to-end trace inspection across 100% of sample messages.

## Assumptions

- **Synthetic data only**: All fixtures and tests use synthetic patient data; no real PHI is ever committed to the repository or run through any environment (constitution Principle III and Security & Compliance Posture).
- **Scope is defended**: This feature implements ADT^A01 only; ADT^A02/A03/A04/A08 and all other HL7 message types are deferred. Transport is HTTP POST only; MLLP, file drop, SFTP are deferred. FHIR resources are limited to Patient and Encounter; other resources (Observation, AllergyIntolerance, etc.) are out of scope (constitution Principle X).
- **HL7 version**: Inbound messages are assumed to declare HL7 v2.5 or v2.5.1 in MSH-12. Messages declaring other versions are rejected at the validation boundary.
- **Idempotency key composition**: The idempotency key is `MSH-10` combined with the value of `MSH-3` (sending application). This is reasonable for a single-sender demo and aligns with HL7 conventions for message uniqueness across senders.
- **Single operator persona**: There is no multi-tenancy and no role-based access control in this feature. All operators see all messages. Audit events still capture an actor identity (e.g., a static operator identifier for the demo) so the audit shape mirrors production.
- **Read-only FHIR API**: The FHIR REST API in scope supports `read` only for Patient and Encounter; FHIR `create`, `update`, `delete`, `search`, and other interactions are out of scope.
- **Replay semantics**: Replay re-runs validation and transformation against the persisted raw HL7 body. The MSH-10 / MSH-3 idempotency key ensures replay never multiplies FHIR resources. Replays themselves emit a distinct audit event so they are distinguishable from original ingestion in the audit trail.
- **Inspector UI deployment**: The Inspector is a web application served alongside the hub for local-demo use; the specific frontend framework choice (e.g., Angular) is a planning decision and is not part of this spec's contract.
- **Audit retention**: Audit events are retained for the lifetime of the demo environment with no automatic purge (constitution Security & Compliance Posture).
- **Authentication**: The ingestion endpoint, FHIR REST API, and Inspector UI are intended for local-demo / synthetic-data use; production-grade authentication and RBAC are explicitly out of scope per Principle X. Any concrete auth choice (e.g., a static shared token) is deferred to the plan.
- **Message size**: A reasonable maximum HL7 message size limit (typical ADT^A01 messages are a few KB) will be enforced at the HTTP boundary; the exact byte limit is a planning decision.
- **Out-of-scope deferrals require an ADR**: Any expansion of the listed scope (additional message types, additional transports, additional FHIR resources, multi-tenancy, RBAC, production deployment) requires an ADR per constitution Principle X.
