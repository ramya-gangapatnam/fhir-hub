package io.github.ramyagangapatnam.fhirhub.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads {@code X-Correlation-Id} from the request or generates a UUID v4, binds it to MDC under
 * {@code correlation_id}, and echoes it on the response. Ordered first so every downstream filter
 * and log line carries the id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Correlation-Id";
  public static final String MDC_KEY = "correlation_id";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String supplied = request.getHeader(HEADER);
    String correlationId = parseUuidOrNull(supplied);
    if (correlationId == null) {
      correlationId = UUID.randomUUID().toString();
    }

    MDC.put(MDC_KEY, correlationId);
    response.setHeader(HEADER, correlationId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  // inbound_message.correlation_id is uuid NOT NULL and audit-event.schema.json requires
  // correlationId format:uuid. Accept only RFC 4122 UUIDs from the client; anything else gets a
  // freshly-minted server-side UUID per plan.md §3.1 ("if absent or malformed, server generates").
  private static String parseUuidOrNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(value).toString();
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }
}
