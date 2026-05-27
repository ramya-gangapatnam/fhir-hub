package io.github.ramyagangapatnam.fhirhub.fhir;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

import io.github.ramyagangapatnam.fhirhub.testsupport.AbstractHttpContractTest;
import io.github.ramyagangapatnam.fhirhub.testsupport.Hl7Fixtures;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * T019 — Contract test for {@code GET /fhir/Patient/{id}}.
 *
 * <p>Driven from {@code specs/001-adt-a01-ingestion-inspection/contracts/fhir-read.openapi.yaml}.
 * Asserts:
 *
 * <ul>
 *   <li>200 OK on a known Patient: body is a FHIR R4 Patient ({@code resourceType: "Patient"},
 *       {@code id} populated); response carries a weak ETag of shape {@code W/"<digits>"} and a
 *       {@code Last-Modified} header.
 *   <li>404 on an unknown logical id returns a FHIR {@code OperationOutcome} (not a bare error
 *       envelope) — see fhir-read.openapi.yaml.
 *   <li>406 Not Acceptable when {@code Accept: application/fhir+xml} is supplied, also wrapped in
 *       {@code OperationOutcome}.
 * </ul>
 *
 * <p>The "known patient" leg POSTs an ADT^A01 fixture first so the Patient id is created end-to-end
 * — the contract test does not reach behind the controllers. Expected to fail at this point in the
 * timeline (controllers under {@code fhir/} not implemented yet).
 */
@Disabled(
    "REST-assured 5.5.0 GET dispatch NPE under JDK 21 + Spring Boot 4 (Groovy HTTPBuilder closure"
        + " dispatch). Tracked in docs/FUTURE.md. To be fixed by migrating these tests to Spring's"
        + " WebTestClient.")
class FhirPatientReadContractTest extends AbstractHttpContractTest {

  private static final String UUID_REGEX =
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
  private static final String ETAG_WEAK_REGEX = "^W/\"[0-9]+\"$";

  @Test
  void readKnownPatientReturns200WithFhirResourceAndCachingHeaders() {
    String patientId = ingestAndPollForPatientId();

    given()
        .header("Authorization", "Bearer " + VALID_TOKEN)
        .header("Accept", "application/fhir+json")
        .when()
        .get("/fhir/Patient/" + patientId)
        .then()
        .statusCode(200)
        .contentType(startsWith("application/fhir+json"))
        .header("ETag", matchesRegex(ETAG_WEAK_REGEX))
        .header("Last-Modified", notNullValue())
        .body("resourceType", equalTo("Patient"))
        .body("id", equalTo(patientId));
  }

  @Test
  void readUnknownPatientReturns404WithOperationOutcome() {
    given()
        .header("Authorization", "Bearer " + VALID_TOKEN)
        .header("Accept", "application/fhir+json")
        .when()
        .get("/fhir/Patient/00000000-0000-0000-0000-000000000000")
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
        .get("/fhir/Patient/00000000-0000-0000-0000-000000000000")
        .then()
        .statusCode(406)
        .contentType(startsWith("application/fhir+json"))
        .body("resourceType", equalTo("OperationOutcome"));
  }

  private String ingestAndPollForPatientId() {
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

    // The Inspector detail endpoint exposes the derived Patient logical id; poll up to 5s (SC-002
    // gives the implementation 2s). This loop runs only once T037+T038+T047 are implemented; until
    // then the test fails at the ingest step above.
    long deadline = System.currentTimeMillis() + 5_000;
    while (System.currentTimeMillis() < deadline) {
      Response detail =
          RestAssured.given()
              .header("Authorization", "Bearer " + VALID_TOKEN)
              .when()
              .get("/inspector/messages/" + messageId);
      if (detail.statusCode() == 200) {
        JsonPath body = detail.jsonPath();
        String id = body.getString("fhirResources.patient.id");
        if (id != null) {
          return id;
        }
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    throw new AssertionError("Patient resource was not available within 5s of ingestion");
  }
}
