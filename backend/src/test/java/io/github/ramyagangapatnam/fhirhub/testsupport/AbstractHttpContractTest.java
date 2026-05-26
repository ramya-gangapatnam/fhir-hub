package io.github.ramyagangapatnam.fhirhub.testsupport;

import io.github.ramyagangapatnam.fhirhub.FhirHubApplication;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * Shared base for the User Story 1 HTTP contract tests (T017–T020).
 *
 * <p>Boots the full Spring application stack on a random port via REST-assured. Persistence-layer
 * autoconfigurations are switched off through Spring's {@code spring.autoconfigure.exclude}
 * property so the contract tests do not require a running Postgres. Tests assert HTTP-level
 * behaviour (status codes, headers, response body shape) — they intentionally have no opinion on
 * persistence.
 *
 * <p>Per the User Story 1 tests-first batch (Principle IV): these contract tests are EXPECTED to
 * fail at this point in the timeline because the controllers under {@code ingestion/} and {@code
 * fhir/} have not been implemented yet. Failures should manifest as "endpoint missing" (404) or
 * security/serialization mismatches rather than test-code defects.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = FhirHubApplication.class)
@TestPropertySource(
    properties = {
      "fhir-hub.auth.token=test-token",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
          + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
          + "org.springframework.boot.data.jpa.autoconfigure.JpaRepositoriesAutoConfiguration,"
          + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
    })
public abstract class AbstractHttpContractTest {

  protected static final String VALID_TOKEN = "test-token";

  @LocalServerPort private int port;

  @BeforeEach
  void configureRestAssured() {
    RestAssured.port = port;
    RestAssured.baseURI = "http://localhost";
    RestAssured.config =
        RestAssuredConfig.config()
            .encoderConfig(
                EncoderConfig.encoderConfig()
                    .encodeContentTypeAs("application/hl7-v2", io.restassured.http.ContentType.TEXT)
                    .appendDefaultContentCharsetToContentTypeIfUndefined(false));
  }
}
