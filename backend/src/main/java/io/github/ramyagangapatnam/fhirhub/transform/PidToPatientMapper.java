package io.github.ramyagangapatnam.fhirhub.transform;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.util.Terser;
import ca.uhn.hl7v2.validation.impl.NoValidation;
import io.github.ramyagangapatnam.fhirhub.error.DomainException;
import io.github.ramyagangapatnam.fhirhub.error.ErrorCode;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
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
 * <p>The mapper does not mint the logical id; the caller passes it in so the {@code (resource_type,
 * id)} uniqueness contract in data-model.md §2.4 stays with the persistence layer (T033). Parsing
 * uses HAPI HL7's {@link PipeParser} with {@link NoValidation} — by the time this mapper runs,
 * {@link io.github.ramyagangapatnam.fhirhub.hl7.Adt01SchemaValidator} has already established the
 * message is well-formed enough to transform.
 */
public final class PidToPatientMapper {

  private final PipeParser parser;

  public PidToPatientMapper() {
    HapiContext ctx = new DefaultHapiContext();
    ctx.setValidationContext(new NoValidation());
    this.parser = ctx.getPipeParser();
  }

  /**
   * Maps the PID segment carried inside {@code rawHl7} onto a {@link Patient} whose logical id is
   * {@code patientLogicalId}. The caller is responsible for assigning the id; the mapper does not
   * mint one so the {@code (resource_type, id)} uniqueness contract in data-model.md §2.4 stays
   * with the persistence layer.
   */
  public Patient toPatient(String rawHl7, String patientLogicalId) {
    Terser t = terser(rawHl7);

    Patient patient = new Patient();
    patient.setId(patientLogicalId);

    addIdentifier(patient, t);
    addName(patient, t);
    addBirthDate(patient, t);
    addGender(patient, t);

    return patient;
  }

  private Terser terser(String rawHl7) {
    try {
      Message msg = parser.parse(rawHl7);
      return new Terser(msg);
    } catch (HL7Exception ex) {
      throw new DomainException(
          ErrorCode.FHIR_TRANSFORM_INTERNAL_ERROR,
          "PID segment could not be parsed for transformation.",
          "PID",
          ex);
    }
  }

  private static void addIdentifier(Patient patient, Terser t) {
    String idValue = terserGet(t, "/PID-3(0)-1");
    String assigningAuthority = terserGet(t, "/PID-3(0)-4");
    if (idValue.isEmpty()) {
      return;
    }
    Identifier ident = patient.addIdentifier();
    ident.setValue(idValue);
    if (!assigningAuthority.isEmpty()) {
      // FHIR identifier systems are nominally URIs. We use a stable URN form derived from the
      // PID-3-4 assigning authority so multiple HL7 senders that share an authority collapse to
      // the same FHIR identifier system.
      ident.setSystem("urn:fhir-hub:assigning-authority:" + assigningAuthority);
    }
  }

  private static void addName(Patient patient, Terser t) {
    String family = terserGet(t, "/PID-5(0)-1");
    String given1 = terserGet(t, "/PID-5(0)-2");
    String given2 = terserGet(t, "/PID-5(0)-3");
    if (family.isEmpty() && given1.isEmpty() && given2.isEmpty()) {
      return;
    }
    HumanName name = patient.addName();
    if (!family.isEmpty()) {
      name.setFamily(family);
    }
    if (!given1.isEmpty()) {
      name.addGiven(given1);
    }
    if (!given2.isEmpty()) {
      name.addGiven(given2);
    }
  }

  private static void addBirthDate(Patient patient, Terser t) {
    String hl7Date = terserGet(t, "/PID-7-1");
    if (hl7Date.length() < 8) {
      return;
    }
    String iso =
        hl7Date.substring(0, 4) + "-" + hl7Date.substring(4, 6) + "-" + hl7Date.substring(6, 8);
    patient.setBirthDateElement(new DateType(iso));
  }

  private static void addGender(Patient patient, Terser t) {
    String sex = terserGet(t, "/PID-8-1");
    patient.setGender(mapGender(sex));
  }

  private static AdministrativeGender mapGender(String hl7Sex) {
    return switch (hl7Sex == null ? "" : hl7Sex.toUpperCase()) {
      case "M" -> AdministrativeGender.MALE;
      case "F" -> AdministrativeGender.FEMALE;
      case "O" -> AdministrativeGender.OTHER;
      case "" -> AdministrativeGender.UNKNOWN;
      default -> AdministrativeGender.UNKNOWN;
    };
  }

  private static String terserGet(Terser t, String spec) {
    try {
      String value = t.get(spec);
      return value == null ? "" : value;
    } catch (HL7Exception ex) {
      return "";
    }
  }
}
