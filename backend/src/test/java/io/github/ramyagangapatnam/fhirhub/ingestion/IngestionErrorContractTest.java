package io.github.ramyagangapatnam.fhirhub.ingestion;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.github.ramyagangapatnam.fhirhub.testsupport.AbstractHttpContractTest;
import io.github.ramyagangapatnam.fhirhub.testsupport.Hl7Fixtures;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

/**
 * T018 — Contract test for the error paths of POST {@code /ingest/hl7v2}.
 *
 * <p>Driven from {@code specs/001-adt-a01-ingestion-inspection/contracts/ingestion.openapi.yaml} +
 * {@code contracts/error-codes.md}. Each branch verifies:
 *
 * <ul>
 *   <li>The HTTP status code listed in the OpenAPI contract.
 *   <li>The {@code error.code} from the stable allow-list.
 *   <li>{@code error.correlationId} is populated.
 *   <li>The full response body contains <strong>zero</strong> PHI tokens from the curated list
 *       ({@link Hl7Fixtures#PHI_TOKENS}). Principle I (NON-NEGOTIABLE).
 * </ul>
 *
 * <p>Expected to fail until T030 (Adt01SchemaValidator) and T037 (IngestionController) land.
 */
class IngestionErrorContractTest extends AbstractHttpContractTest {

  @Test
  void missingPidSegmentReturns400AndHl7ParseMissingSegment() {
    Response response =
        given()
            .header("Authorization", "Bearer " + VALID_TOKEN)
            .header("Content-Type", "application/hl7-v2")
            .body(Hl7Fixtures.MISSING_PID)
            .when()
            .post("/ingest/hl7v2");

    response
        .then()
        .statusCode(400)
        .body("error.code", equalTo("HL7_PARSE_MISSING_SEGMENT"))
        .body("error.correlationId", notNullValue());

    assertResponseHasNoPhi(response);
  }

  @Test
  void badFramingReturns400AndHl7ParseInvalidFraming() {
    Response response =
        given()
            .header("Authorization", "Bearer " + VALID_TOKEN)
            .header("Content-Type", "application/hl7-v2")
            .body(Hl7Fixtures.BAD_FRAMING)
            .when()
            .post("/ingest/hl7v2");

    response
        .then()
        .statusCode(400)
        .body("error.code", equalTo("HL7_PARSE_INVALID_FRAMING"))
        .body("error.correlationId", notNullValue());

    assertResponseHasNoPhi(response);
  }

  @Test
  void wrongVersionReturns400AndHl7ParseUnsupportedVersion() {
    Response response =
        given()
            .header("Authorization", "Bearer " + VALID_TOKEN)
            .header("Content-Type", "application/hl7-v2")
            .body(Hl7Fixtures.WRONG_VERSION)
            .when()
            .post("/ingest/hl7v2");

    response
        .then()
        .statusCode(400)
        .body("error.code", equalTo("HL7_PARSE_UNSUPPORTED_VERSION"))
        .body("error.correlationId", notNullValue());

    assertResponseHasNoPhi(response);
  }

  @Test
  void oversizedBodyReturns413AndHttpPayloadTooLarge() {
    // 64 KiB + a margin; synthetic non-PHI filler so the body remains over the cap and the
    // assertion focuses on the cap itself, not any PHI shape.
    StringBuilder filler = new StringBuilder(70 * 1024);
    filler
        .append("MSH|^~\\&|ADMIT_SYSTEM|HOSPITAL|RECEIVER|FACILITY|20260520143210||ADT^A01|")
        .append("OVERSIZE|P|2.5\r");
    while (filler.length() < 70 * 1024) {
      filler.append("NTE|1|X|FILLER-SEGMENT-NOT-PHI-").append(filler.length()).append("\r");
    }

    Response response =
        given()
            .header("Authorization", "Bearer " + VALID_TOKEN)
            .header("Content-Type", "application/hl7-v2")
            .body(filler.toString())
            .when()
            .post("/ingest/hl7v2");

    response
        .then()
        .statusCode(413)
        .body("error.code", equalTo("HTTP_PAYLOAD_TOO_LARGE"))
        .body("error.correlationId", notNullValue());
  }

  @Test
  void wrongContentTypeReturns415AndHttpUnsupportedMediaType() {
    Response response =
        given()
            .header("Authorization", "Bearer " + VALID_TOKEN)
            .header("Content-Type", "text/plain")
            .body(Hl7Fixtures.GOOD)
            .when()
            .post("/ingest/hl7v2");

    response
        .then()
        .statusCode(415)
        .body("error.code", equalTo("HTTP_UNSUPPORTED_MEDIA_TYPE"))
        .body("error.correlationId", notNullValue());

    assertResponseHasNoPhi(response);
  }

  private static void assertResponseHasNoPhi(Response response) {
    String body = response.asString();
    for (String token : Hl7Fixtures.PHI_TOKENS) {
      assertThat(body)
          .as("Response body must not echo PHI token <%s>", token)
          .doesNotContain(token);
    }
  }
}
