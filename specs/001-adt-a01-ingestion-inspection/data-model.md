# Phase 1 — Data Model

**Feature**: ADT^A01 Ingestion and Inspection
**Date**: 2026-05-20

This document is the concrete data-model contract for the feature. Tables, constraints, indexes, and the FHIR persistence approach are spelled out here; the [plan](./plan.md) cross-references this file.

## 1. Schema overview

```text
                         ┌───────────────────────┐
                         │   inbound_message     │
                         │  (one row per POST)   │
                         └──────────┬────────────┘
                                    │ 1
                                    │
                ┌───────────────────┼───────────────────┐
              0..N                  │ 0..1              │ 0..1
                │                   │                   │
                ▼                   ▼                   ▼
       ┌─────────────────┐   ┌────────────────────────┐
       │ validation_error│   │   idempotency_key       │
       │                 │   │  (sender, msh10) UNIQUE │
       └─────────────────┘   └──────────┬─────────────┘
                                        │ 2 (Patient + Encounter)
                                        ▼
                              ┌──────────────────────┐
                              │   fhir_resource      │
                              │  (Patient |          │
                              │   Encounter, JSONB)  │
                              └──────────────────────┘
```

## 2. Tables

### 2.1 `inbound_message`

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `uuid` | PK, `default gen_random_uuid()` | The "hub-assigned message identifier" returned in the ingestion 202 response. |
| `raw_hl7` | `text` | `not null` | The exact bytes POSTed, UTF-8. Source of truth for replay. |
| `msh10_control_id` | `varchar(199)` | `not null` | Extracted from MSH-10 during the lightweight pre-persistence parse. |
| `msh3_sending_application` | `varchar(180)` | `not null` | Extracted from MSH-3. |
| `received_at_utc` | `timestamptz` | `not null`, `default now()` | UTC timestamp of arrival. |
| `status` | `text` | `not null`, `check (status in ('RECEIVED','VALIDATING','TRANSFORMED','PERSISTED','FAILED'))` | Lifecycle state. See §3 for transitions. |
| `correlation_id` | `uuid` | `not null` | Echoed from `X-Correlation-Id` or server-generated. |
| `last_error_code` | `varchar(80)` | nullable | Code from the most recent validation/transformation failure. |
| `last_error_location` | `varchar(160)` | nullable | Segment.field index, e.g., `PID(missing)` or `PID-7`. Never a value. |
| `created_at_utc` | `timestamptz` | `not null`, `default now()` | Audit baseline. |
| `updated_at_utc` | `timestamptz` | `not null`, `default now()` | Updated by `before update` trigger. |

**Indexes**:
- `idx_inbound_message_received_at_desc` on `received_at_utc desc` — Inspector list default ordering.
- `idx_inbound_message_status` on `status` — Inspector list filter (FR-012).
- `idx_inbound_message_msh10` on `msh10_control_id` — Inspector search (FR-012).
- `idx_inbound_message_correlation_id` on `correlation_id` — diagnostic lookups from log lines.

### 2.2 `validation_error`

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `uuid` | PK, `default gen_random_uuid()` | |
| `inbound_message_id` | `uuid` | `not null`, FK → `inbound_message(id)` `on delete cascade` | |
| `error_code` | `varchar(80)` | `not null` | From the fixed allow-list (see `contracts/error-codes.md`). |
| `segment_field` | `varchar(160)` | nullable | Same shape as `inbound_message.last_error_location`. |
| `summary_safe` | `varchar(500)` | `not null` | Display-safe summary; MUST NOT echo source-message values. |
| `created_at_utc` | `timestamptz` | `not null`, `default now()` | |

**Indexes**:
- `idx_validation_error_inbound_message_id` on `inbound_message_id`.

### 2.3 `idempotency_key`

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `uuid` | PK, `default gen_random_uuid()` | |
| `sending_application` | `varchar(180)` | `not null` | Copy of `inbound_message.msh3_sending_application` for the canonical version of this message. |
| `msh10_control_id` | `varchar(199)` | `not null` | |
| `inbound_message_id` | `uuid` | `not null`, FK → `inbound_message(id)` | The *first* inbound message that established this key. Replays do NOT update this column. |
| `patient_resource_id` | `text` | nullable, FK → `fhir_resource(id)` | Set when transformation persists a Patient. |
| `encounter_resource_id` | `text` | nullable, FK → `fhir_resource(id)` | Set when transformation persists an Encounter. |
| `created_at_utc` | `timestamptz` | `not null`, `default now()` | |
| `updated_at_utc` | `timestamptz` | `not null`, `default now()` | |

**Constraints**:
- `unique (sending_application, msh10_control_id)` — Principle VIII enforcement at the DB layer. Concurrent POSTs of the same message produce one row; the loser raises `UNIQUE_VIOLATION` which the application converts to a non-creating success.

### 2.4 `fhir_resource`

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `text` | PK | FHIR logical id. UUID-shaped string but kept as text so we can interoperate with non-UUID ids if a future ADR allows them. |
| `resource_type` | `text` | `not null`, `check (resource_type in ('Patient','Encounter'))` | Scope is fixed by Principle X; the check expands only via ADR. |
| `version_id` | `bigint` | `not null`, `default 1` | Bumped on each upsert; used for FHIR `ETag` header. |
| `last_updated_utc` | `timestamptz` | `not null`, `default now()` | Used for FHIR `Last-Modified` header. |
| `content_json` | `jsonb` | `not null` | HAPI FHIR `IParser`-serialized R4 resource. |
| `business_identifier_system` | `varchar(255)` | nullable | For Patient, the PID-3 identifier system; for Encounter, the PV1 visit-number system if present. |
| `business_identifier_value` | `varchar(255)` | nullable | Corresponding value. |
| `created_at_utc` | `timestamptz` | `not null`, `default now()` | |

**Constraints**:
- `unique (resource_type, business_identifier_system, business_identifier_value) where business_identifier_value is not null` — second-layer idempotency for the Patient identifier-collision edge case in the spec.

**Indexes**:
- `idx_fhir_resource_type_id` on `(resource_type, id)` — the FHIR `read` query path.

## 3. Lifecycle and transitions for `inbound_message.status`

```text
   POST received, body persisted
            │
            ▼
       RECEIVED ─────────────────────────┐
            │                            │
            │  processor picks up        │
            ▼                            │
       VALIDATING                        │ (replay re-enters here
            │                            │   from PERSISTED or FAILED)
            │                            │
   ┌────────┴────────┐                   │
   │                 │                   │
   ▼                 ▼                   │
TRANSFORMED      FAILED ◀───┐            │
   │                        │            │
   │                        │  replay    │
   ▼                        │  re-runs   │
PERSISTED ──────────────────┘            │
   │                                     │
   └──── replay ─────────────────────────┘
```

- `RECEIVED → VALIDATING`: the in-process processor begins work.
- `VALIDATING → TRANSFORMED`: HL7 schema validation and FHIR mapping completed in memory.
- `TRANSFORMED → PERSISTED`: upserts to `fhir_resource` and `idempotency_key` committed.
- `* → FAILED`: any failure along the path. The transition records `last_error_code` and `last_error_location`, and creates a `validation_error` row.
- Replay always restarts at `VALIDATING` against the existing `raw_hl7`.

## 4. FHIR persistence approach

Recap of the decision (full reasoning in [research.md §2](./research.md)):

- **One table, `fhir_resource`, with JSONB content.** Scope is two FHIR resource types (Patient, Encounter, Principle X). A per-resource-type schema would duplicate the parser/serializer layer and force a schema migration for every future resource type.
- **HAPI FHIR `IParser` is the serialization boundary.** Read controllers fetch the row, call `parser.parseResource(Patient.class, content_json)` (or stream the JSONB directly back as the response body — both are correct; the parsed object is only needed when the application has to *act* on the resource).
- **Idempotency is enforced twice**:
  1. By `idempotency_key (sending_application, msh10_control_id)` — handles "same MSH-10 + sender" exactly.
  2. By `fhir_resource (resource_type, business_identifier_system, business_identifier_value)` — handles the Patient-identifier-collision edge case where two distinct messages map to the same Patient identifier (spec edge case "Patient identifier collision").
- **No FHIR `search` table or indexing infrastructure.** Out of scope (Principle X). Add a separate ADR if/when needed.

## 5. Migrations

Flyway migrations live at `backend/src/main/resources/db/migration/`. Initial migrations:

| File | Purpose |
|---|---|
| `V1__inbound_message.sql` | `inbound_message` + indexes + `updated_at` trigger. |
| `V2__validation_error.sql` | `validation_error` + index + FK. |
| `V3__fhir_resource.sql` | `fhir_resource` + check constraint + indexes. |
| `V4__idempotency_key.sql` | `idempotency_key` + unique constraint + FKs. |
| `V5__functions_triggers.sql` | `updated_at` trigger function shared across tables. |

Naming and numbering follow Flyway conventions. Migrations are forward-only; reverts happen via a new forward migration.

## 6. Volume and retention

- **Expected volume** (demo): under 10k rows total across the lifetime of a demo environment. No archival policy needed.
- **Retention**: lifetime of the demo environment. No automatic purge (Spec assumption, constitution Security & Compliance Posture).
- **Sizing implication for RDS `db.t4g.micro`**: 20 GB gp3 storage is far more than this volume needs; we provision the minimum to stay free-tier-eligible.
