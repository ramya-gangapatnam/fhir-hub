package io.github.ramyagangapatnam.fhirhub.transform;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import io.github.ramyagangapatnam.fhirhub.testsupport.Hl7Fixtures;
import org.hl7.fhir.r4.model.Encounter;
import org.junit.jupiter.api.Test;

/**
 * T023 — Unit test for {@link Pv1ToEncounterMapper}.
 *
 * <p>The GOOD fixture's PV1 segment carries:
 *
 * <ul>
 *   <li>PV1-2 = {@code I} (inpatient encounter class)
 *   <li>PV1-3 = {@code 2W^201^A^GEN} (point-of-care / room / bed / facility location)
 *   <li>PV1-4 = {@code R} (admission type — routine)
 *   <li>PV1-7 = {@code DR_SMITH^WELBY^MARCUS} (attending provider)
 * </ul>
 *
 * <p>Assertions:
 *
 * <ul>
 *   <li>{@code Encounter.class.code} reflects PV1-2 (inpatient → {@code IMP} per FHIR v3 ActCode).
 *   <li>{@code Encounter.location[0].location.reference} carries the PV1-3 derived location.
 *   <li>{@code Encounter.type[0]} carries the PV1-4 admission-type coding.
 *   <li>{@code Encounter.participant[0].individual.reference} carries the PV1-7 attending
 *       reference.
 *   <li>{@code Encounter.subject.reference} resolves to {@code Patient/<patientLogicalId>}.
 *   <li>Round-trips through HAPI FHIR {@link IParser} to JSON without loss.
 * </ul>
 *
 * <p>Expected to fail with {@link UnsupportedOperationException} until T032 lands.
 */
class Pv1ToEncounterMapperTest {

  private static final FhirContext FHIR = FhirContext.forR4();
  private static final String PATIENT_ID = "11111111-1111-1111-1111-111111111111";
  private static final String ENCOUNTER_ID = "22222222-2222-2222-2222-222222222222";

  private final Pv1ToEncounterMapper mapper = new Pv1ToEncounterMapper();

  @Test
  void mapsPv1TwoEncounterClass() {
    Encounter encounter = mapper.toEncounter(Hl7Fixtures.GOOD, ENCOUNTER_ID, PATIENT_ID);

    assertThat(encounter.getClass_()).isNotNull();
    assertThat(encounter.getClass_().getCode())
        .as("PV1-2 'I' must map to inpatient class code 'IMP'")
        .isEqualTo("IMP");
  }

  @Test
  void mapsPv1ThreeLocationReference() {
    Encounter encounter = mapper.toEncounter(Hl7Fixtures.GOOD, ENCOUNTER_ID, PATIENT_ID);

    assertThat(encounter.getLocation()).isNotEmpty();
    String locationRef = encounter.getLocationFirstRep().getLocation().getReference();
    assertThat(locationRef)
        .as("PV1-3 location reference must reflect the assigned PoC/room/bed")
        .contains("2W");
  }

  @Test
  void mapsPv1FourAdmissionType() {
    Encounter encounter = mapper.toEncounter(Hl7Fixtures.GOOD, ENCOUNTER_ID, PATIENT_ID);

    assertThat(encounter.getType()).isNotEmpty();
    String code =
        encounter.getTypeFirstRep().getCodingFirstRep().getCode() == null
            ? ""
            : encounter.getTypeFirstRep().getCodingFirstRep().getCode();
    assertThat(code).as("PV1-4 admission type 'R' must surface on Encounter.type").contains("R");
  }

  @Test
  void mapsPv1SevenAttendingParticipant() {
    Encounter encounter = mapper.toEncounter(Hl7Fixtures.GOOD, ENCOUNTER_ID, PATIENT_ID);

    assertThat(encounter.getParticipant()).isNotEmpty();
    String attendingRef =
        encounter.getParticipantFirstRep().getIndividual().getReference() == null
            ? ""
            : encounter.getParticipantFirstRep().getIndividual().getReference();
    assertThat(attendingRef)
        .as("PV1-7 attending provider must surface on Encounter.participant[0].individual")
        .contains("DR_SMITH");
  }

  @Test
  void setsSubjectReferenceToPatientLogicalId() {
    Encounter encounter = mapper.toEncounter(Hl7Fixtures.GOOD, ENCOUNTER_ID, PATIENT_ID);

    assertThat(encounter.getSubject().getReference()).isEqualTo("Patient/" + PATIENT_ID);
  }

  @Test
  void assignsCallerSuppliedLogicalId() {
    Encounter encounter = mapper.toEncounter(Hl7Fixtures.GOOD, ENCOUNTER_ID, PATIENT_ID);

    assertThat(encounter.getIdElement().getIdPart()).isEqualTo(ENCOUNTER_ID);
  }

  @Test
  void roundTripsThroughHapiIParserWithoutLoss() {
    Encounter original = mapper.toEncounter(Hl7Fixtures.GOOD, ENCOUNTER_ID, PATIENT_ID);

    IParser parser = FHIR.newJsonParser();
    String json = parser.encodeResourceToString(original);
    Encounter parsed = parser.parseResource(Encounter.class, json);

    assertThat(parsed.getIdElement().getIdPart()).isEqualTo(original.getIdElement().getIdPart());
    assertThat(parsed.getClass_().getCode()).isEqualTo(original.getClass_().getCode());
    assertThat(parsed.getSubject().getReference()).isEqualTo(original.getSubject().getReference());
  }
}
