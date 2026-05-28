package io.github.ramyagangapatnam.fhirhub.inspector;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Backend for the Angular Inspector SPA (plan.md §3.4–§3.6). Lists ingested messages, returns one
 * message's raw HL7 + derived FHIR side-by-side, and replays a message against its persisted raw
 * body.
 *
 * <p>The list view (this file, T046) is metadata-only — it NEVER returns the raw HL7 body, so the
 * default operator view exposes no PHI. The detail and replay endpoints (T047, T048) return the raw
 * body and emit audit events because that access is authenticated and audited (Principle II).
 */
@RestController
@RequestMapping("/inspector")
public class InspectorController {

  static final int DEFAULT_LIMIT = 50;
  static final int MAX_LIMIT = 200;

  private final JdbcTemplate jdbc;

  public InspectorController(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  /**
   * {@code GET /inspector/messages}: status filter (single or repeated), {@code msh10} exact-match
   * search, {@code received_at_utc} descending order, and {@code limit}/{@code offset} pagination
   * (limit clamped to [1, 200], default 50). The raw HL7 body is intentionally excluded from the
   * projection.
   */
  @GetMapping("/messages")
  public MessageListPage list(
      @RequestParam(name = "status", required = false) List<String> status,
      @RequestParam(name = "msh10", required = false) String msh10,
      @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit,
      @RequestParam(name = "offset", defaultValue = "0") int offset) {

    int effectiveLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
    int effectiveOffset = Math.max(offset, 0);

    StringBuilder where = new StringBuilder();
    List<Object> filterArgs = new ArrayList<>();
    if (status != null && !status.isEmpty()) {
      where.append(where.isEmpty() ? " WHERE " : " AND ").append("status IN (");
      for (int i = 0; i < status.size(); i++) {
        where.append(i == 0 ? "?" : ",?");
        filterArgs.add(status.get(i));
      }
      where.append(")");
    }
    if (msh10 != null && !msh10.isBlank()) {
      where.append(where.isEmpty() ? " WHERE " : " AND ").append("msh10_control_id = ?");
      filterArgs.add(msh10);
    }

    Long total =
        jdbc.queryForObject(
            "SELECT count(*) FROM inbound_message" + where, Long.class, filterArgs.toArray());

    List<Object> pageArgs = new ArrayList<>(filterArgs);
    pageArgs.add(effectiveLimit);
    pageArgs.add(effectiveOffset);
    List<MessageListItem> messages =
        jdbc.query(
            "SELECT id, msh10_control_id, msh3_sending_application, received_at_utc, status,"
                + " last_error_code FROM inbound_message"
                + where
                + " ORDER BY received_at_utc DESC LIMIT ? OFFSET ?",
            (rs, rowNum) ->
                new MessageListItem(
                    rs.getObject("id", UUID.class).toString(),
                    rs.getString("msh10_control_id"),
                    rs.getString("msh3_sending_application"),
                    rs.getObject("received_at_utc", OffsetDateTime.class).toString(),
                    rs.getString("status"),
                    rs.getString("last_error_code")),
            pageArgs.toArray());

    return new MessageListPage(
        messages, new Page(effectiveLimit, effectiveOffset, total == null ? 0L : total));
  }

  /** One row in the list view — metadata only, no raw HL7. */
  public record MessageListItem(
      String messageId,
      String msh10ControlId,
      String sendingApplication,
      String receivedAtUtc,
      String status,
      String lastErrorCode) {}

  /** Pagination envelope. */
  public record Page(int limit, int offset, long total) {}

  /** A page of list items. */
  public record MessageListPage(List<MessageListItem> messages, Page page) {}
}
