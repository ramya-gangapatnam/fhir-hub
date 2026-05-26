package io.github.ramyagangapatnam.fhirhub.idempotency;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ramyagangapatnam.fhirhub.testsupport.AbstractDbIntegrationTest;
import io.github.ramyagangapatnam.fhirhub.testsupport.Hl7Fixtures;
import io.restassured.response.Response;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * T024 — Integration test for idempotency at the ingestion boundary.
 *
 * <p>Covers SC-003: three concurrent POSTs of the same GOOD fixture MUST result in:
 *
 * <ul>
 *   <li>Three rows in {@code inbound_message} (one per POST — the raw body is always persisted).
 *   <li>Exactly one row in {@code idempotency_key} (enforced by the {@code (sending_application,
 *       msh10_control_id)} unique constraint from V4).
 *   <li>Exactly one Patient and one Encounter row in {@code fhir_resource} (the two FHIR resources
 *       derived from this one logical admission).
 *   <li>Each of the three 202 responses carries the same downstream resource ids (every caller
 *       observes the same Patient and Encounter id).
 * </ul>
 *
 * <p>Principle VIII (Idempotent Ingestion). Expected to fail until T028 + T034 + T037 land.
 */
class IdempotencyIntegrationTest extends AbstractDbIntegrationTest {

  @Test
  void threeConcurrentPostsOfSameMessageYieldSingleDownstreamSet() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(3);
    try {
      List<Callable<Response>> tasks = new ArrayList<>();
      for (int i = 0; i < 3; i++) {
        tasks.add(
            () ->
                given()
                    .header("Authorization", "Bearer " + VALID_TOKEN)
                    .header("Content-Type", "application/hl7-v2")
                    .body(Hl7Fixtures.GOOD)
                    .when()
                    .post("/ingest/hl7v2"));
      }

      List<Future<Response>> futures = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);
      Set<String> messageIds = new HashSet<>();
      for (Future<Response> f : futures) {
        Response r = f.get();
        assertThat(r.statusCode()).isEqualTo(202);
        messageIds.add(r.jsonPath().getString("messageId"));
      }
      assertThat(messageIds)
          .as("Each POST persists a distinct inbound_message and so returns a distinct messageId")
          .hasSize(3);

      // Allow the asynchronous transformation to settle.
      waitForRows("fhir_resource", "resource_type = 'Patient'", 1, 5_000);
      waitForRows("fhir_resource", "resource_type = 'Encounter'", 1, 5_000);

      assertThat(countRows("inbound_message", null)).isEqualTo(3);
      assertThat(countRows("idempotency_key", null))
          .as("Unique (sending_application, msh10_control_id) collapses all replays into one row")
          .isEqualTo(1);
      assertThat(countRows("fhir_resource", "resource_type = 'Patient'")).isEqualTo(1);
      assertThat(countRows("fhir_resource", "resource_type = 'Encounter'")).isEqualTo(1);

      Set<String> patientIds = collectIdempotencyResourceIds("patient_resource_id");
      Set<String> encounterIds = collectIdempotencyResourceIds("encounter_resource_id");
      assertThat(patientIds).as("All three replays must observe the same Patient id").hasSize(1);
      assertThat(encounterIds)
          .as("All three replays must observe the same Encounter id")
          .hasSize(1);
    } finally {
      pool.shutdownNow();
    }
  }

  private static int countRows(String table, String predicate) throws Exception {
    String sql = "SELECT COUNT(*) FROM " + table + (predicate == null ? "" : " WHERE " + predicate);
    try (Connection c = openConnection();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private static Set<String> collectIdempotencyResourceIds(String column) throws Exception {
    Set<String> ids = new HashSet<>();
    try (Connection c = openConnection();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT " + column + " FROM idempotency_key")) {
      while (rs.next()) {
        ids.add(rs.getString(1));
      }
    }
    return ids;
  }

  private static void waitForRows(String table, String predicate, int min, long timeoutMs)
      throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (countRows(table, predicate) >= min) {
        return;
      }
      Thread.sleep(100);
    }
  }

  private static Connection openConnection() throws Exception {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
