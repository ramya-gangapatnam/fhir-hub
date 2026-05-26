package io.github.ramyagangapatnam.fhirhub.transform;

import org.hl7.fhir.r4.model.Encounter;

/**
 * Maps an HL7 v2 PV1 segment onto a FHIR R4 {@link Encounter}. Fields covered per plan.md §3.1:
 *
 * <ul>
 *   <li>PV1-2 → {@link Encounter#getClass_()} (encounter class coding)
 *   <li>PV1-3 → {@link Encounter#getLocation()} (assigned location)
 *   <li>PV1-4 → encounter type ({@link Encounter#getType()}) for admission type
 *   <li>PV1-7 → {@code participant.individual.reference} (attending provider)
 * </ul>
 *
 * <p>The Encounter's {@link Encounter#getSubject()} is set to {@code Patient/<patientLogicalId>} so
 * the FHIR client can resolve the Patient via the FHIR read endpoint.
 *
 * <p><strong>Compilation stub — real implementation lands in T032.</strong> Method body throws
 * {@link UnsupportedOperationException} so the T023 unit test fails honestly against missing code.
 * Principle IV (Test-First for Business Logic).
 */
public final class Pv1ToEncounterMapper {

  /**
   * Maps the PV1 segment carried inside {@code rawHl7} onto an {@link Encounter} whose logical id
   * is {@code encounterLogicalId} and whose {@code subject.reference} is {@code
   * Patient/<patientLogicalId>}.
   */
  public Encounter toEncounter(String rawHl7, String encounterLogicalId, String patientLogicalId) {
    throw new UnsupportedOperationException("Pv1ToEncounterMapper pending T032");
  }
}
