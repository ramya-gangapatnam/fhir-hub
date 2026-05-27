package io.github.ramyagangapatnam.fhirhub.testsupport;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One Postgres 16 container shared across every Spring Boot integration / contract test class in
 * the JVM. Without this, each {@code @SpringBootTest} that {@code extends AbstractDbIntegrationTest}
 * or {@code AbstractHttpContractTest} spins up its own container; Docker on a dev laptop falls
 * over once 5+ containers stack up concurrently (we observed connection-refused races on Windows).
 *
 * <p>Started eagerly on first class load and reused for the rest of the JVM. Testcontainers'
 * Ryuk reaper still cleans it up at JVM shutdown — we just opt out of the per-class
 * {@code @Testcontainers} lifecycle.
 */
public final class SharedPostgres {

  public static final PostgreSQLContainer<?> INSTANCE =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("fhirhub")
          .withUsername("fhirhub")
          .withPassword("fhirhub");

  static {
    INSTANCE.start();
  }

  private SharedPostgres() {}
}
