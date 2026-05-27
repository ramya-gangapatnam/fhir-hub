package io.github.ramyagangapatnam.fhirhub.idempotency;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Primary Principle VIII (Idempotent Ingestion) enforcement point. Wraps the {@code
 * idempotency_key} table from Flyway V4: the unique constraint on {@code (sending_application,
 * msh10_control_id)} is what makes concurrent POSTs of the same message collapse to a single
 * downstream FHIR resource set.
 *
 * <p>{@link #reserve(String, String, UUID)} performs {@code INSERT ... ON CONFLICT DO NOTHING
 * RETURNING ...}: the winner of the race observes {@link Outcome.Won}, the losers observe {@link
 * Outcome.AlreadyReserved} with the established row's {@code patient_resource_id} and {@code
 * encounter_resource_id} (which may still be {@code null} if the winner is mid-transform — the
 * caller short-circuits and waits for the winner's pipeline to set them; the next read of those
 * columns from a fresh transaction will see the populated values).
 *
 * <p>This is the seam that converts {@code PERSIST_IDEMPOTENCY_CONFLICT} into an idempotent
 * success. Callers (T035 {@code MessageTransformationService}) MUST treat {@link
 * Outcome.AlreadyReserved} as "the message has been seen; do not create new FHIR rows," not as a
 * failure.
 */
@Component
public class IdempotencyArbiter {

  private final JdbcTemplate jdbc;

  public IdempotencyArbiter(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  /**
   * Attempt to reserve the idempotency key for {@code (sendingApplication, msh10ControlId)},
   * binding the FIRST inbound message that established it.
   *
   * <p>Runs in a {@code REQUIRES_NEW} transaction so the insert is committed independently of the
   * surrounding pipeline. This matters because two concurrent processors must observe each other's
   * commits via the DB unique-violation path rather than through dirty reads inside a shared
   * transaction.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Outcome reserve(String sendingApplication, String msh10ControlId, UUID inboundMessageId) {
    Objects.requireNonNull(sendingApplication, "sendingApplication");
    Objects.requireNonNull(msh10ControlId, "msh10ControlId");
    Objects.requireNonNull(inboundMessageId, "inboundMessageId");

    // INSERT ... ON CONFLICT DO NOTHING RETURNING id — the winner sees a row, losers see no row.
    UUID newKeyId =
        jdbc.query(
            "INSERT INTO idempotency_key "
                + "(sending_application, msh10_control_id, inbound_message_id) "
                + "VALUES (?, ?, ?) "
                + "ON CONFLICT (sending_application, msh10_control_id) DO NOTHING "
                + "RETURNING id",
            rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            sendingApplication,
            msh10ControlId,
            inboundMessageId);

    if (newKeyId != null) {
      return new Outcome.Won(newKeyId);
    }

    // Conflict: the row already exists. Read it back so the caller can short-circuit to PERSISTED
    // with the winner's resource ids.
    return jdbc.queryForObject(
        "SELECT id, patient_resource_id, encounter_resource_id "
            + "FROM idempotency_key "
            + "WHERE sending_application = ? AND msh10_control_id = ?",
        (rs, rowNum) ->
            new Outcome.AlreadyReserved(
                rs.getObject("id", UUID.class),
                rs.getString("patient_resource_id"),
                rs.getString("encounter_resource_id")),
        sendingApplication,
        msh10ControlId);
  }

  /**
   * Update the idempotency key with the Patient + Encounter logical ids produced by the winner's
   * transformation. Idempotent — calling twice with the same arguments is a no-op modulo the {@code
   * updated_at_utc} stamp.
   */
  @Transactional
  public void bindResources(
      UUID idempotencyKeyId, String patientResourceId, String encounterResourceId) {
    Objects.requireNonNull(idempotencyKeyId, "idempotencyKeyId");
    jdbc.update(
        "UPDATE idempotency_key "
            + "SET patient_resource_id = ?, encounter_resource_id = ?, updated_at_utc = now() "
            + "WHERE id = ?",
        patientResourceId,
        encounterResourceId,
        idempotencyKeyId);
  }

  /**
   * Re-read the idempotency key — used by tests and by the replay path to learn the currently bound
   * FHIR resource ids.
   */
  @Transactional(readOnly = true)
  public Optional<Outcome.AlreadyReserved> lookup(
      String sendingApplication, String msh10ControlId) {
    try {
      Outcome.AlreadyReserved row =
          jdbc.queryForObject(
              "SELECT id, patient_resource_id, encounter_resource_id "
                  + "FROM idempotency_key "
                  + "WHERE sending_application = ? AND msh10_control_id = ?",
              (rs, rowNum) ->
                  new Outcome.AlreadyReserved(
                      rs.getObject("id", UUID.class),
                      rs.getString("patient_resource_id"),
                      rs.getString("encounter_resource_id")),
              sendingApplication,
              msh10ControlId);
      return Optional.ofNullable(row);
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  /** Outcome of {@link #reserve(String, String, UUID)}. */
  public sealed interface Outcome {

    /** The caller won the race; the new {@code idempotency_key} row was just inserted. */
    record Won(UUID idempotencyKeyId) implements Outcome {}

    /**
     * Another caller already reserved this key. The losing pipeline MUST treat this as an
     * idempotent success and MUST NOT create new FHIR resources. The bound resource ids may be
     * {@code null} if the winner has not finished transforming yet — that is expected; the eventual
     * state will be consistent because the winner is in flight.
     */
    record AlreadyReserved(
        UUID idempotencyKeyId, String patientResourceId, String encounterResourceId)
        implements Outcome {}
  }
}
