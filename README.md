# HL7 v2 → FHIR R4 Interoperability Hub

A portfolio project demonstrating production-grade engineering discipline applied to a focused healthcare interoperability problem: ingesting legacy HL7 v2 messages, transforming them into FHIR R4 resources, persisting them, and exposing them via a REST API with an inspector UI.

## Status

🚧 **Early setup — spec not yet written.**

This project is being built using Spec-Driven Development with [Spec Kit](https://github.com/github/spec-kit). The constitution, spec, plan, and tasks will be authored before any production code is written.

## Scope

**Demo-sized:** one HL7 message type (ADT^A01 patient admission), HTTP ingestion, Inspector UI, ~1–2 user stories.

**Production-grade discipline:** test-first, OpenTelemetry observability, security defaults, infrastructure as code, CI/CD with merge gates.

More detail will follow as the spec is authored.
