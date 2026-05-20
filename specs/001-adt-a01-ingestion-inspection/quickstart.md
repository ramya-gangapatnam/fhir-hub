# Quickstart — 5-Minute Onboarding

**Feature**: ADT^A01 Ingestion and Inspection
**Target acceptance criterion**: [SC-008](./spec.md#measurable-outcomes) — a new contributor clones, ingests a fixture, and views the message in the Inspector within 5 minutes.

This is the path-of-least-resistance walkthrough. Deeper guidance (rebuilds, troubleshooting, AWS deploy) lives in the root `README.md`.

## Prerequisites

Installed on the contributor's machine before they clone:

- Git
- Docker Desktop (or any Docker Engine ≥ 24) with `docker compose` v2 available on `PATH`
- A modern browser

That's it. The Compose stack carries the JVM, Node, Postgres, and LocalStack S3.

## The five-minute path

```bash
# 1. Clone
git clone https://github.com/ramya-gangapatnam/fhir-hub.git
cd fhir-hub

# 2. Bring up Postgres + backend + frontend + LocalStack S3
docker compose -f docker/docker-compose.yml up -d --build

# 3. Wait for backend health (Compose health-check gates the others; ~20s typical)
docker compose -f docker/docker-compose.yml ps
# Look for status "healthy" on `backend` before continuing.

# 4. POST a known-good ADT^A01 fixture via the bundled test client
./test-client/post-fixture.sh fixtures/adt-a01-good.hl7
# Prints the response body, including messageId and X-Correlation-Id.

# 5. Open the Inspector
#   macOS:  open http://localhost:4200
#   Linux:  xdg-open http://localhost:4200
#   Win:    start http://localhost:4200
#   (Or just visit the URL in any browser.)
```

When the Inspector loads it requests the default bearer token from a local
`.env` file already wired into Compose (`FHIR_HUB_AUTH_TOKEN`). The most-recent
message — the one you just POSTed — appears at the top of the list with status
`PERSISTED`. Click into it to see the raw HL7 body on the left and the derived
FHIR Patient and Encounter resources on the right.

That is the satisfaction of SC-008.

## What if something fails

The fixture set includes a deliberately broken message so contributors can
exercise the failure path too:

```bash
./test-client/post-fixture.sh fixtures/adt-a01-missing-pid.hl7
```

This message reaches `inbound_message` with status `FAILED` and produces a
`validation_error` row with `error_code = HL7_PARSE_MISSING_SEGMENT`. In the
Inspector the entry shows as `FAILED`; the detail view shows the structured
validation error. Click **Replay** — the message stays `FAILED` because the
raw body is still malformed, but a new audit event is written. To see a
successful replay path, swap in the good fixture and replay that one (it
remains `PERSISTED` and does not duplicate FHIR resources, per Principle VIII).

## Where things live

| What | Where |
|---|---|
| Backend logs | `docker compose logs -f backend` (JSON, correlation_id on every line) |
| Audit JSONL | `var/audit/audit.log` (bind-mounted from the host) |
| LocalStack S3 audit objects (if `AUDIT_SINK=s3` in `.env`) | `awslocal s3 ls s3://fhir-hub-audit-dev/audit/year=YYYY/...` |
| Postgres shell | `docker compose exec postgres psql -U fhirhub -d fhirhub` |
| FHIR REST read by id | `curl -H "Authorization: Bearer $FHIR_HUB_AUTH_TOKEN" http://localhost:8080/fhir/Patient/<id>` |

## Stopping cleanly

```bash
docker compose -f docker/docker-compose.yml down -v
```

`-v` drops the Postgres and LocalStack volumes so the next run starts from a
clean state. Omit `-v` to keep your ingested messages between sessions.

## Running tests locally

```bash
# Java unit + Testcontainers integration
./gradlew test integrationTest

# Angular unit
cd frontend && npm test -- --watch=false

# End-to-end (boots the Compose stack)
./gradlew e2eTest
```

## Deploying to AWS

See `infra/terraform/README.md`. In summary: `terraform init`, set
`db_master_password` and `auth_token` in `terraform.tfvars` (or, better,
via env vars), `terraform apply`. The output prints the ALB DNS name.
The README explicitly notes that the static bearer token is demo-only
and would be replaced by OAuth/OIDC in production.
