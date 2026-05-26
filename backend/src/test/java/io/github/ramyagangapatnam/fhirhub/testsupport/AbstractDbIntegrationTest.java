package io.github.ramyagangapatnam.fhirhub.testsupport;

import io.github.ramyagangapatnam.fhirhub.FhirHubApplication;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Shared base for the User Story 1 integration tests (T024–T027) that need real persistence:
 * idempotency uniqueness, audit emission, correlation-ID propagation through the DB, and PHI
 * redaction in observability output.
 *
 * <p>Boots the full Spring Boot application against a real Postgres 16 container (Testcontainers)
 * with Flyway enabled — so the schema, indexes, and CHECK constraints from T006–T010 are exercised
 * exactly as in production.
 *
 * <p>Expected behaviour at this point in the timeline (Principle IV, tests-first batch): the tests
 * fail because the controllers / processor / transformation service / audit sink under {@code
 * ingestion/}, {@code processing/}, {@code transform/}, and {@code audit/} have not been
 * implemented yet (T028–T041). The schema migrates cleanly, the app boots, and HTTP requests get
 * 404 / 401 / 500 instead of the expected 202 / 200 / specific error envelopes.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = FhirHubApplication.class)
@TestPropertySource(properties = {"fhir-hub.auth.token=test-token"})
public abstract class AbstractDbIntegrationTest {

  protected static final String VALID_TOKEN = "test-token";

  @Container
  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("fhirhub")
          .withUsername("fhirhub")
          .withPassword("fhirhub");

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
  }

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
