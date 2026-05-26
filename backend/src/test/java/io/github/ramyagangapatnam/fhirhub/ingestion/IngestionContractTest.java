package io.github.ramyagangapatnam.fhirhub.ingestion;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.notNullValue;

import io.github.ramyagangapatnam.fhirhub.testsupport.AbstractHttpContractTest;
import io.github.ramyagangapatnam.fhirhub.testsupport.Hl7Fixtures;
import org.junit.jupiter.api.Test;

/**
 * T017 — Contract test for the happy-path POST {@code /ingest/hl7v2}.
 *
 * <p>Driven from {@code specs/001-adt-a01-ingestion-inspection/contracts/ingestion.openapi.yaml}.
 *
 * <p>Asserts:
 *
 * <ul>
 *   <li>HTTP 202 Accepted on a valid ADT^A01 with the expected {@code Content-Type} and bearer
 *       token.
 *   <li>Response body conforms to the {@code IngestAcceptedResponse} schema: {@code messageId}
 *       (UUID), {@code status = RECEIVED}, {@code receivedAtUtc} (ISO-8601), {@code correlationId}
 *       (UUID).
 *   <li>When the caller supplies {@code X-Correlation-Id}, the same id is echoed in the response
 *       header AND in the {@code correlationId} body field. (SC-009 surface check.)
 * </ul>
 *
 * <p>This test is EXPECTED to fail until T037 (IngestionController) lands — Principle IV.
 */
class IngestionContractTest extends AbstractHttpContractTest {

  private static final String UUID_REGEX =
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
  private static final String ISO_8601_UTC_REGEX =
      "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?(Z|[+-]\\d{2}:?\\d{2})$";

  @Test
  void postGoodFixtureReturns202WithIngestAcceptedResponseBody() {
    given()
        .header("Authorization", "Bearer " + VALID_TOKEN)
        .header("Content-Type", "application/hl7-v2")
        .body(Hl7Fixtures.GOOD)
        .when()
        .post("/ingest/hl7v2")
        .then()
        .statusCode(202)
        .header("X-Correlation-Id", notNullValue())
        .body("messageId", matchesRegex(UUID_REGEX))
        .body("status", equalTo("RECEIVED"))
        .body("receivedAtUtc", matchesRegex(ISO_8601_UTC_REGEX))
        .body("correlationId", matchesRegex(UUID_REGEX));
  }

  @Test
  void suppliedCorrelationIdIsEchoedOnHeaderAndBody() {
    String correlationId = "11111111-2222-3333-4444-555555555555";

    given()
        .header("Authorization", "Bearer " + VALID_TOKEN)
        .header("Content-Type", "application/hl7-v2")
        .header("X-Correlation-Id", correlationId)
        .body(Hl7Fixtures.GOOD)
        .when()
        .post("/ingest/hl7v2")
        .then()
        .statusCode(202)
        .header("X-Correlation-Id", equalTo(correlationId))
        .body("correlationId", equalTo(correlationId));
  }

  @Test
  void missingBearerTokenReturns401WithMissingTokenCode() {
    given()
        .header("Content-Type", "application/hl7-v2")
        .body(Hl7Fixtures.GOOD)
        .when()
        .post("/ingest/hl7v2")
        .then()
        .statusCode(401)
        .body("error.code", equalTo("HTTP_AUTH_MISSING_TOKEN"))
        .body("error.correlationId", notNullValue());
  }
}
