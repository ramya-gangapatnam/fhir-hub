package io.github.ramyagangapatnam.fhirhub.testsupport;

import io.github.ramyagangapatnam.fhirhub.FhirHubApplication;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared base for the User Story 2 Inspector contract / integration tests (T042–T045).
 *
 * <p>Boots the full Spring application on a random port against a real Postgres 16 (Testcontainers)
 * and exposes a {@link WebTestClient} bound to that server. The Inspector tests deliberately use
 * {@code WebTestClient} rather than REST-assured: REST-assured 5.5.0 throws a {@link
 * NullPointerException} on every HTTP GET under JDK 21 + Spring Boot 4 (see {@code
 * docs/FUTURE.md}), which is fatal for the Inspector's GET-heavy surface. {@code WebTestClient}
 * drives the server over a native reactor-netty client and is unaffected.
 *
 * <p>Per Principle IV (tests-first): these tests were written and observed to fail (404 — no
 * Inspector handlers) before T046–T048 implemented {@code InspectorController}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = FhirHubApplication.class)
@TestPropertySource(properties = {"fhir-hub.auth.token=test-token"})
public abstract class AbstractInspectorWebTest {

  protected static final String VALID_TOKEN = "test-token";

  // One Postgres across the JVM — see SharedPostgres for the why.
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

  /** Bearer-authenticated client bound to the running server; rebuilt per test. */
  protected WebTestClient client;

  @BeforeEach
  void initWebTestClient() {
    client =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
            .responseTimeout(Duration.ofSeconds(20))
            .build();
  }

  /**
   * The Postgres container is shared across test classes, so wipe per-test state before each test
   * to keep row-count and pagination assertions deterministic. Idempotent on a fresh DB.
   */
  @BeforeEach
  void truncateBusinessTables() throws SQLException {
    try (Connection c =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement s = c.createStatement()) {
      s.execute(
          "TRUNCATE TABLE idempotency_key, validation_error, fhir_resource, inbound_message"
              + " RESTART IDENTITY CASCADE");
    }
  }
}
