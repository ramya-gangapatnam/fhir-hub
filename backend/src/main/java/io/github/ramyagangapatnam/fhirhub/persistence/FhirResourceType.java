package io.github.ramyagangapatnam.fhirhub.persistence;

/**
 * FHIR resource types persisted by the hub. Mirrors the CHECK constraint installed by Flyway V3
 * ({@code chk_fhir_resource_type}). Scope is fixed by Principle X — adding a value requires an ADR
 * and a forward Flyway migration to widen the CHECK.
 */
public enum FhirResourceType {
  Patient,
  Encounter
}
