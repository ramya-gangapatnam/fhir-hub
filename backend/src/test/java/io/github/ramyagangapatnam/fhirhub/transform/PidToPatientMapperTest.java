package io.github.ramyagangapatnam.fhirhub.transform;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import io.github.ramyagangapatnam.fhirhub.testsupport.Hl7Fixtures;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;

/**
 * T022 — Unit test for {@link PidToPatientMapper}.
 *
 * <p>Pins the PID → Patient mapping contract from plan.md §3.1:
 *
 * <ul>
 *   <li>PID-3 ({@code MRN0001234^^^HOSP^MR}) → {@code Patient.identifier[0]} with the {@code HOSP}
 *       system and {@code MRN0001234} value.
 *   <li>PID-5 ({@code DOEPATIENT^JANE^ELIZABETH}) → {@code Patient.name[0].family = "DOEPATIENT"},
 *       {@code given = ["JANE", "ELIZABETH"]}.
 *   <li>PID-7 ({@code 19850203}) → {@code Patient.birthDate = 1985-02-03}.
 *   <li>PID-8 ({@code F}) → {@code Patient.gender = female}.
 *   <li>The mapped {@link Patient} round-trips through HAPI FHIR {@link IParser} to JSON and back
 *       without loss.
 * </ul>
 *
 * <p>Expected to fail with {@link UnsupportedOperationException} until T031 lands.
 */
class PidToPatientMapperTest {

  private static final FhirContext FHIR = FhirContext.forR4();
  private static final String PATIENT_ID = "11111111-1111-1111-1111-111111111111";

  private final PidToPatientMapper mapper = new PidToPatientMapper();

  @Test
  void mapsPidFiveNameComponents() {
    Patient patient = mapper.toPatient(Hl7Fixtures.GOOD, PATIENT_ID);

    assertThat(patient.getName()).hasSize(1);
    HumanName name = patient.getNameFirstRep();
    assertThat(name.getFamily()).isEqualTo("DOEPATIENT");
    assertThat(name.getGiven())
        .extracting(stringType -> stringType.getValue())
        .containsExactly("JANE", "ELIZABETH");
  }

  @Test
  void mapsPidThreeIdentifierSystemAndValue() {
    Patient patient = mapper.toPatient(Hl7Fixtures.GOOD, PATIENT_ID);

    assertThat(patient.getIdentifier()).isNotEmpty();
    Identifier ident = patient.getIdentifierFirstRep();
    assertThat(ident.getValue()).isEqualTo("MRN0001234");
    assertThat(ident.getSystem())
        .as("Identifier system must reflect PID-3 assigning authority")
        .contains("HOSP");
  }

  @Test
  void mapsPidSevenBirthDate() {
    Patient patient = mapper.toPatient(Hl7Fixtures.GOOD, PATIENT_ID);

    assertThat(patient.getBirthDateElement().getValueAsString()).isEqualTo("1985-02-03");
  }

  @Test
  void mapsPidEightGender() {
    Patient patient = mapper.toPatient(Hl7Fixtures.GOOD, PATIENT_ID);

    assertThat(patient.getGender()).isEqualTo(AdministrativeGender.FEMALE);
  }

  @Test
  void assignsCallerSuppliedLogicalId() {
    Patient patient = mapper.toPatient(Hl7Fixtures.GOOD, PATIENT_ID);

    assertThat(patient.getIdElement().getIdPart()).isEqualTo(PATIENT_ID);
  }

  @Test
  void roundTripsThroughHapiIParserWithoutLoss() {
    Patient original = mapper.toPatient(Hl7Fixtures.GOOD, PATIENT_ID);

    IParser parser = FHIR.newJsonParser();
    String json = parser.encodeResourceToString(original);
    Patient parsed = parser.parseResource(Patient.class, json);

    assertThat(parsed.getIdElement().getIdPart()).isEqualTo(original.getIdElement().getIdPart());
    assertThat(parsed.getNameFirstRep().getFamily())
        .isEqualTo(original.getNameFirstRep().getFamily());
    assertThat(parsed.getNameFirstRep().getGivenAsSingleString())
        .isEqualTo(original.getNameFirstRep().getGivenAsSingleString());
    assertThat(parsed.getIdentifierFirstRep().getValue())
        .isEqualTo(original.getIdentifierFirstRep().getValue());
    assertThat(parsed.getIdentifierFirstRep().getSystem())
        .isEqualTo(original.getIdentifierFirstRep().getSystem());
    assertThat(parsed.getBirthDateElement().getValueAsString())
        .isEqualTo(original.getBirthDateElement().getValueAsString());
    assertThat(parsed.getGender()).isEqualTo(original.getGender());
  }
}
