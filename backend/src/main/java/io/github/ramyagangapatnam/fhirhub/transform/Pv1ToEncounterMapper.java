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
import java.util.ArrayList;
import java.util.List;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Encounter.EncounterLocationComponent;
import org.hl7.fhir.r4.model.Encounter.EncounterParticipantComponent;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.stereotype.Component;

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
 * <p>Parsing uses HAPI HL7's {@link PipeParser} with {@link NoValidation} — by the time this mapper
 * runs, {@link io.github.ramyagangapatnam.fhirhub.hl7.Adt01SchemaValidator} has already established
 * the message is well-formed enough to transform.
 */
@Component
public final class Pv1ToEncounterMapper {

  private static final String ACT_CODE_SYSTEM = "http://terminology.hl7.org/CodeSystem/v3-ActCode";
  private static final String ADMIT_TYPE_SYSTEM = "http://terminology.hl7.org/CodeSystem/v2-0007";

  private final PipeParser parser;

  public Pv1ToEncounterMapper() {
    HapiContext ctx = new DefaultHapiContext();
    ctx.setValidationContext(new NoValidation());
    this.parser = ctx.getPipeParser();
  }

  /**
   * Maps the PV1 segment carried inside {@code rawHl7} onto an {@link Encounter} whose logical id
   * is {@code encounterLogicalId} and whose {@code subject.reference} is {@code
   * Patient/<patientLogicalId>}.
   */
  public Encounter toEncounter(String rawHl7, String encounterLogicalId, String patientLogicalId) {
    Terser t = terser(rawHl7);

    Encounter encounter = new Encounter();
    encounter.setId(encounterLogicalId);
    encounter.setSubject(new Reference("Patient/" + patientLogicalId));

    setClass(encounter, t);
    setLocation(encounter, t);
    setAdmissionType(encounter, t);
    setAttendingParticipant(encounter, t);

    return encounter;
  }

  private Terser terser(String rawHl7) {
    try {
      Message msg = parser.parse(rawHl7);
      return new Terser(msg);
    } catch (HL7Exception ex) {
      throw new DomainException(
          ErrorCode.FHIR_TRANSFORM_INTERNAL_ERROR,
          "PV1 segment could not be parsed for transformation.",
          "PV1",
          ex);
    }
  }

  private static void setClass(Encounter encounter, Terser t) {
    String hl7Class = terserGet(t, "/PV1-2");
    if (hl7Class.isEmpty()) {
      return;
    }
    String fhirCode = mapPatientClass(hl7Class);
    encounter.setClass_(new Coding().setSystem(ACT_CODE_SYSTEM).setCode(fhirCode));
  }

  /**
   * HL7 v2 PV1-2 patient class to FHIR R4 Encounter.class (v3 ActCode). Unmapped values fall
   * through to the original code so downstream tooling can still surface the value rather than
   * silently losing it.
   */
  private static String mapPatientClass(String hl7Class) {
    return switch (hl7Class.toUpperCase()) {
      case "I" -> "IMP"; // inpatient
      case "O" -> "AMB"; // outpatient -> ambulatory
      case "E" -> "EMER"; // emergency
      case "P" -> "PRENC"; // preadmit
      case "B" -> "IMP"; // obstetrics treated as inpatient
      default -> hl7Class.toUpperCase();
    };
  }

  private static void setLocation(Encounter encounter, Terser t) {
    String pointOfCare = terserGet(t, "/PV1-3-1");
    String room = terserGet(t, "/PV1-3-2");
    String bed = terserGet(t, "/PV1-3-3");
    String facility = terserGet(t, "/PV1-3-4");
    if (pointOfCare.isEmpty() && room.isEmpty() && bed.isEmpty() && facility.isEmpty()) {
      return;
    }
    String slug = joinNonEmpty("-", pointOfCare, room, bed, facility);
    EncounterLocationComponent loc = encounter.addLocation();
    loc.setLocation(new Reference("Location/" + slug));
  }

  private static void setAdmissionType(Encounter encounter, Terser t) {
    String admitType = terserGet(t, "/PV1-4");
    if (admitType.isEmpty()) {
      return;
    }
    CodeableConcept cc = new CodeableConcept();
    cc.addCoding(new Coding().setSystem(ADMIT_TYPE_SYSTEM).setCode(admitType));
    encounter.addType(cc);
  }

  private static void setAttendingParticipant(Encounter encounter, Terser t) {
    String practId = terserGet(t, "/PV1-7-1");
    String family = terserGet(t, "/PV1-7-2");
    String given = terserGet(t, "/PV1-7-3");
    if (practId.isEmpty() && family.isEmpty() && given.isEmpty()) {
      return;
    }
    EncounterParticipantComponent participant = encounter.addParticipant();
    String practRef = practId.isEmpty() ? joinNonEmpty("-", family, given) : practId;
    participant.setIndividual(new Reference("Practitioner/" + practRef));
  }

  private static String joinNonEmpty(String separator, String... parts) {
    List<String> nonEmpty = new ArrayList<>();
    for (String p : parts) {
      if (p != null && !p.isEmpty()) {
        nonEmpty.add(p);
      }
    }
    return String.join(separator, nonEmpty);
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
