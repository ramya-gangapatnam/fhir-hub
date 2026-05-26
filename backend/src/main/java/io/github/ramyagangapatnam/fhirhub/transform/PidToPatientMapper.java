package io.github.ramyagangapatnam.fhirhub.transform;

import org.hl7.fhir.r4.model.Patient;

/**
 * Maps an HL7 v2 PID segment onto a FHIR R4 {@link Patient}. Fields covered per plan.md §3.1 and
 * data-model.md §2.4:
 *
 * <ul>
 *   <li>PID-3 → {@link Patient#getIdentifier()} (system + value)
 *   <li>PID-5 → {@link Patient#getName()} (family + given)
 *   <li>PID-7 → {@link Patient#getBirthDate()} (YYYYMMDD)
 *   <li>PID-8 → {@link Patient#getGender()}
 * </ul>
 *
 * <p><strong>Compilation stub — real implementation lands in T031.</strong> Method body throws
 * {@link UnsupportedOperationException} so the T022 unit test fails honestly against missing code.
 * Principle IV (Test-First for Business Logic).
 */
public final class PidToPatientMapper {

  /**
   * Maps the PID segment carried inside {@code rawHl7} onto a {@link Patient} whose logical id is
   * {@code patientLogicalId}. The caller is responsible for assigning the id; the mapper does not
   * mint one so the {@code (resource_type, id)} uniqueness contract in data-model.md §2.4 stays
   * with the persistence layer.
   */
  public Patient toPatient(String rawHl7, String patientLogicalId) {
    throw new UnsupportedOperationException("PidToPatientMapper pending T031");
  }
}
