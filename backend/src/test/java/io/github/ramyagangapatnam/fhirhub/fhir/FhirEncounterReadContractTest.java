package io.github.ramyagangapatnam.fhirhub.fhir;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

import io.github.ramyagangapatnam.fhirhub.testsupport.AbstractHttpContractTest;
import io.github.ramyagangapatnam.fhirhub.testsupport.Hl7Fixtures;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

/**
 * T020 — Contract test for {@code GET /fhir/Encounter/{id}}.
 *
 * <p>Parallel structure to {@link FhirPatientReadContractTest} (T019), plus the additional
 * assertion that {@code Encounter.subject.reference} resolves to the related Patient by FHIR
 * logical id ({@code Patient/<patientId>}). The reference is the contract surface for the
 * Patient↔Encounter relationship; if T032 stops emitting it, this test catches the regression.
 *
 * <p>Expected to fail until T032 (Pv1ToEncounterMapper) + T038 (FhirReadController) land.
 */
class FhirEncounterReadContractTest extends AbstractHttpContractTest {

  private static final String UUID_REGEX =
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
  private static final String ETAG_WEAK_REGEX = "^W/\"[0-9]+\"$";

  @Test
  void readKnownEncounterReturns200WithFhirResourceAndCachingHeaders() {
    String[] ids = ingestAndPollForResourceIds();
    String patientId = ids[0];
    String encounterId = ids[1];

    Response encounter =
        given()
            .header("Authorization", "Bearer " + VALID_TOKEN)
            .header("Accept", "application/fhir+json")
            .when()
            .get("/fhir/Encounter/" + encounterId);

    encounter
        .then()
        .statusCode(200)
        .contentType(startsWith("application/fhir+json"))
        .header("ETag", matchesRegex(ETAG_WEAK_REGEX))
        .header("Last-Modified", notNullValue())
        .body("resourceType", equalTo("Encounter"))
        .body("id", equalTo(encounterId));

    String subjectRef = encounter.jsonPath().getString("subject.reference");
    assertThat(subjectRef)
        .as("Encounter.subject.reference must point at the related Patient")
        .isEqualTo("Patient/" + patientId);
  }

  @Test
  void readUnknownEncounterReturns404WithOperationOutcome() {
    given()
        .header("Authorization", "Bearer " + VALID_TOKEN)
        .header("Accept", "application/fhir+json")
        .when()
        .get("/fhir/Encounter/00000000-0000-0000-0000-000000000000")
        .then()
        .statusCode(404)
        .contentType(startsWith("application/fhir+json"))
        .body("resourceType", equalTo("OperationOutcome"))
        .body("issue[0].severity", equalTo("error"))
        .body("issue[0].code", equalTo("not-found"));
  }

  @Test
  void unsupportedAcceptReturns406WithOperationOutcome() {
    given()
        .header("Authorization", "Bearer " + VALID_TOKEN)
        .header("Accept", "application/fhir+xml")
        .when()
        .get("/fhir/Encounter/00000000-0000-0000-0000-000000000000")
        .then()
        .statusCode(406)
        .contentType(startsWith("application/fhir+json"))
        .body("resourceType", equalTo("OperationOutcome"));
  }

  /** Returns {@code [patientId, encounterId]} once ingestion finishes. */
  private String[] ingestAndPollForResourceIds() {
    Response ingest =
        given()
            .header("Authorization", "Bearer " + VALID_TOKEN)
            .header("Content-Type", "application/hl7-v2")
            .body(Hl7Fixtures.GOOD)
            .when()
            .post("/ingest/hl7v2");

    ingest.then().statusCode(202);
    String messageId = ingest.jsonPath().getString("messageId");
    if (messageId == null || !messageId.matches(UUID_REGEX)) {
      throw new AssertionError("messageId missing from ingestion response");
    }

    long deadline = System.currentTimeMillis() + 5_000;
    while (System.currentTimeMillis() < deadline) {
      Response detail =
          RestAssured.given()
              .header("Authorization", "Bearer " + VALID_TOKEN)
              .when()
              .get("/inspector/messages/" + messageId);
      if (detail.statusCode() == 200) {
        JsonPath body = detail.jsonPath();
        String patientId = body.getString("fhirResources.patient.id");
        String encounterId = body.getString("fhirResources.encounter.id");
        if (patientId != null && encounterId != null) {
          return new String[] {patientId, encounterId};
        }
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    throw new AssertionError(
        "Patient + Encounter resources were not available within 5s of ingestion");
  }
}
