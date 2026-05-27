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

/**
 * Shared base for the User Story 1 HTTP contract tests (T017–T020).
 *
 * <p>Boots the full Spring application stack on a random port against a real Postgres 16
 * (Testcontainers) — once T037 (IngestionController) and T038 (FhirReadController) wire up
 * persistence the contract tests cannot pretend the DB is absent. Tests still assert HTTP-level
 * behaviour (status codes, headers, response body shape); they do not peek into the DB directly.
 *
 * <p>Per the User Story 1 tests-first batch (Principle IV): these contract tests were written
 * before the controllers existed and ran red until T037 / T038 lit them up.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = FhirHubApplication.class)
@TestPropertySource(properties = {"fhir-hub.auth.token=test-token"})
public abstract class AbstractHttpContractTest {

  protected static final String VALID_TOKEN = "test-token";

  // Shared Postgres across the JVM — see SharedPostgres for the why.
  protected static final PostgreSQLContainer<?> POSTGRES = SharedPostgres.INSTANCE;

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
