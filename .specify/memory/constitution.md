<!--
SYNC IMPACT REPORT
Version change: (template, unversioned) → 1.0.0
Type: initial ratification

Modified principles (placeholders → concrete principles):
- I.   PHI Confidentiality in Logs (NON-NEGOTIABLE)         [new]
- II.  PHI Access Auditing (NON-NEGOTIABLE)                 [new]
- III. PHI Encryption at Rest and in Transit (NON-NEGOTIABLE) [new]
- IV.  Test-First for Business Logic                        [new]
- V.   No Merging on Red                                    [new]
- VI.  Secret Hygiene                                       [new]
- VII. Observability from Day One                           [new]
- VIII.Idempotent Ingestion                                 [new]
- IX.  Schema Validation at Boundaries                      [new]
- X.   Demo Scope is Defended                               [new]
- XI.  ADRs for Architectural Decisions                     [new]

Added sections:
- Core Principles (11 principles)
- Security & Compliance Posture
- Development Workflow & Quality Gates
- Governance

Removed sections: none (template placeholders consumed).

Templates requiring updates:
- ✅ .specify/templates/plan-template.md — "Constitution Check" gate is resolved dynamically at /speckit-plan time; no static edit required.
- ✅ .specify/templates/spec-template.md — contains no constitution references; no edit required.
- ⚠ .specify/templates/tasks-template.md — current language ("Tests are OPTIONAL — only include them if explicitly requested in the feature specification") conflicts with Principle IV. Update recommended to: tests for business logic (HL7 parsing, FHIR mapping, validation, idempotency, audit) are MANDATORY; tests for infrastructure scaffolding remain optional.
- ✅ .specify/templates/checklist-template.md — no immediate dependency at v1.0.0.
- ✅ README.md — existing phrasing is consistent with constitution; no edit required.
- ✅ CLAUDE.md — references the active plan only; no edit required.

Follow-up TODOs: none for initial ratification.
-->

# HL7 v2 → FHIR R4 Interoperability Hub Constitution

## Core Principles

### I. PHI Confidentiality in Logs (NON-NEGOTIABLE)

Protected Health Information (PHI) — patient names, identifiers, dates of birth, addresses, MRNs,
raw HL7 message bodies, and any field that could reasonably identify a patient — MUST NOT appear
in application logs, error traces, exception messages, telemetry attributes, stdout, or stderr.
Redaction MUST occur at the emission boundary (logger filters, log processors, span attribute
sanitizers), never as a downstream cleanup pass. Automated tests MUST assert that representative
HL7 messages produce log output containing zero PHI tokens.

**Rationale**: Logs are the highest-volume, lowest-controlled data surface in a healthcare system.
A single PHI leak into a log aggregator is a reportable breach, and "we'll scrub it later" has
historically never worked.

### II. PHI Access Auditing (NON-NEGOTIABLE)

Every read or write of a persisted PHI-bearing resource — Patient, Encounter, message body, and
audit records themselves — MUST emit an audit event capturing actor identity, UTC ISO-8601
timestamp, resource type and ID, operation, and outcome. Audit events MUST be written to an
append-only sink that is distinct from the operational data store. Code paths that touch PHI
without producing an audit event MUST fail code review.

**Rationale**: Audit completeness is the baseline expectation in healthcare. Omitting it signals
naïveté about the domain, and retrofitting it is consistently more painful than building it in.

### III. PHI Encryption at Rest and in Transit (NON-NEGOTIABLE)

All persisted PHI MUST be encrypted at rest, via either database-level encryption or
column-level encryption for sensitive fields. All transport carrying PHI MUST use TLS 1.2 or
higher. Plaintext PHI MUST NOT exist on disk, in object storage, in queue payloads, or in
unencrypted backups. Test fixtures MUST use synthetic data only — never real PHI, even encrypted.

**Rationale**: Encryption gaps are typically introduced by accident (a local dump for debugging,
a forgotten dev queue, a `print()` left in). Making the rule absolute eliminates the
"just this once" failure mode.

### IV. Test-First for Business Logic

HL7 parsing, FHIR mapping, validation, idempotency enforcement, and audit emission MUST have
failing tests written and reviewed before implementation. The Red → Green → Refactor cycle is
required for the transformation and persistence core. Pure-infrastructure code (DB connection
wiring, framework boilerplate, UI scaffolding) is exempt but encouraged to follow the same
pattern.

**Rationale**: HL7 v2 ↔ FHIR R4 mappings carry subtle edge cases — segment ordering, optional
fields, code-system translation, repeat fields. Tests written after the fact tend to lock in
whatever the implementation happens to do, bugs included.

### V. No Merging on Red

A change set MUST NOT merge to the integration branch while any required CI check is failing.
Required checks include unit tests, integration tests, type checking, lint, secret scanning,
and dependency vulnerability scanning. The `--no-verify` flag, force-pushes that bypass branch
protection, and disabled CI gates are prohibited absent explicit, ADR-documented justification.

**Rationale**: Test discipline collapses the moment "just this one merge" is allowed. The gate
exists to be enforced uniformly; conditional enforcement is no enforcement.

### VI. Secret Hygiene

Secrets — API keys, database credentials, signing keys, OAuth client secrets, TLS private keys —
MUST NOT appear in source files, configuration committed to git, container images, or git
history (including reverted commits). Configuration MUST load secrets from environment variables
or a secret manager at runtime. The repository MUST have pre-commit or CI scanning configured to
fail on detected secret patterns. Accidentally committed secrets MUST be rotated, not merely
deleted.

**Rationale**: Git history is forever; a secret "fixed" by deletion or rebase remains valid
attack material until the underlying credential is invalidated.

### VII. Observability from Day One

The system MUST emit structured JSON logs, metrics, and distributed traces from the first
feature merged. OpenTelemetry is the chosen instrumentation surface. Every HTTP request, every
ingested message, and every persistence operation MUST be observable end-to-end via a single
correlation ID. Adding observability later is forbidden — if a feature ships without
instrumentation, it ships broken.

**Rationale**: Retrofitting observability is consistently more expensive than building it in.
Demo scope makes day-one observability cheap: there is nothing yet to retrofit.

### VIII. Idempotent Ingestion

HL7 message ingestion MUST be idempotent with respect to the message control ID (MSH-10) or an
equivalent stable identifier. Replaying the same message MUST NOT create duplicate FHIR
resources, duplicate business audit entries, or otherwise corrupt state. Idempotency MUST be
enforced at the persistence layer (e.g., unique constraint plus upsert semantics), not relied
upon at the client.

**Rationale**: Real-world HL7 senders retry aggressively and unpredictably. Non-idempotent
ingestion produces duplicate patients in production and is essentially unrecoverable without
manual reconciliation.

### IX. Schema Validation at Boundaries

Every external input — incoming HL7 messages, REST API request bodies, configuration loaded at
startup, message-queue payloads — MUST be validated against an explicit schema before reaching
business logic. Validation failures MUST produce structured errors with a stable error code and
MUST NOT leak internal state, stack traces, or PHI. Internal, trusted code paths MAY skip
revalidation, but the trust boundary MUST be explicit.

**Rationale**: Boundary validation localizes "what does malformed input look like?" to one place
per boundary, preventing parsing-vs-validation confusion from leaking through the system.

### X. Demo Scope is Defended

This project's scope — one HL7 message type (ADT^A01 patient admission), HTTP ingestion, an
Inspector UI, and approximately one to two user stories — MUST be defended against expansion.
Out of scope by default: additional message types, additional transports (MLLP/TCP, file drop,
SFTP), FHIR resources beyond what ADT^A01 demands, multi-tenancy, role-based access control,
and "general healthcare integration platform" framing. Scope additions require an ADR
documenting why the expansion is essential to the demo's narrative.

**Rationale**: A portfolio project lives or dies by depth, not breadth. Drift toward a
"real product" doubles the work and dilutes the engineering story this project exists to tell.

### XI. ADRs for Architectural Decisions

Significant architectural decisions MUST be captured as Architecture Decision Records under
`docs/adr/`, one file per decision, numbered sequentially (e.g., `0001-hl7-parser-choice.md`).
Each ADR MUST state context, the decision, alternatives considered, and consequences.
"Significant" includes: choice of HL7 parser, FHIR server vs. custom persistence, database
engine, telemetry backend, deployment target, any scope expansion (Principle X), and any
deviation from another constitution principle.

**Rationale**: Six months from now, the answer to "why this and not that?" must be
reconstructible from the repository, not from memory or chat history.

## Security & Compliance Posture

**Threat model scope**: This project is a portfolio demonstration. It is NOT deployed against
real patient data. Synthetic test data only — real HL7 messages from any real system MUST NOT
be copied into this repository or any of its environments.

**Compliance framing**: HIPAA Security Rule controls are *demonstrated*, not certified. The
constitution treats them as engineering requirements because the discipline matters; no claim
of formal compliance certification is made or implied.

**Data classification**: Any field that could appear in a real ADT^A01 message (PID, PV1, NK1,
GT1, IN1, etc.) MUST be treated as PHI, even when populated with synthetic data, so that the
code path and test posture mirror production patterns.

**Audit retention**: Audit events MUST be retained for the lifetime of the demo environment
with no automatic purge.

**Synthetic data sourcing**: Test fixtures MUST be either hand-authored or generated by a
documented synthetic-data tool; their provenance MUST be recorded in the repository.

## Development Workflow & Quality Gates

**Required CI checks** (all MUST pass before merge):

- Unit tests
- Integration tests for HL7 → FHIR transformation and persistence
- Type checking (per chosen language toolchain)
- Lint and formatting
- Secret scanning
- Dependency vulnerability scanning

**Code review requirements**:

- Every change MUST be reviewed against the active principles.
- Reviewers MUST explicitly check: PHI in logs (I), audit emission (II), secret hygiene (VI),
  test coverage of business logic (IV), and scope (X).
- Deviations from any principle MUST cite an ADR.

**ADR process**:

- ADRs live under `docs/adr/`, named `NNNN-kebab-title.md`.
- Required for: choice of major dependency, deviation from any constitution principle, scope
  expansion (Principle X), and changes to the audit or observability surface.
- An ADR is immutable once accepted; superseding decisions get a new ADR that references the
  previous one.

**Branch and merge discipline**:

- The main branch is protected; all changes arrive via PR.
- Failing required CI = no merge (Principle V).
- `--no-verify`, force-pushes against main, and disabled hooks require ADR-recorded
  justification.

## Governance

This constitution supersedes prior conventions, ad-hoc agreements, and individual preference.
All work performed within this repository MUST conform to it.

**Amendment procedure**:

1. Open a PR modifying `.specify/memory/constitution.md`.
2. Prepend an updated Sync Impact Report summarizing the change.
3. Propagate updates to dependent templates and documentation in the same PR.
4. Increment `Version` per the versioning policy below.
5. Update `Last Amended` to the merge date.

**Versioning policy** (semantic):

- **MAJOR**: Backward-incompatible governance changes — removing a principle, materially
  redefining one, or changing the amendment procedure.
- **MINOR**: Adding a principle, adding a section, or materially expanding a principle's scope.
- **PATCH**: Clarifications, wording, typo fixes — no semantic shift.

**Compliance review**:

- The constitution is referenced explicitly during `/speckit-plan` via the Constitution Check
  gate; plans that violate a principle MUST declare and justify the violation in the plan's
  Complexity Tracking section, with a linked ADR.
- The constitution is referenced during code review (see "Code review requirements" above).

**Runtime guidance**: For per-feature technical context, defer to the active plan under
`specs/<feature>/plan.md` and to ADRs under `docs/adr/`. Where guidance conflicts, the
constitution wins.

**Version**: 1.0.0 | **Ratified**: 2026-05-19 | **Last Amended**: 2026-05-19
