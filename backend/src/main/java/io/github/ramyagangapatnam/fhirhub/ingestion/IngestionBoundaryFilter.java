package io.github.ramyagangapatnam.fhirhub.ingestion;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces the 64 KiB body cap and the {@code application/hl7-v2} content type on the ingestion
 * endpoint. Rejects with the stable error envelope ({@code HTTP_PAYLOAD_TOO_LARGE} → 413, {@code
 * HTTP_UNSUPPORTED_MEDIA_TYPE} → 415) before the request body is read.
 *
 * <p>Ordered <em>after</em> Spring Security ({@code SecurityProperties.DEFAULT_FILTER_ORDER =
 * -100}) so auth is enforced first — an anonymous oversize/wrong-content-type request gets 401, not
 * 413/415.
 *
 * <p>Principle IX (Schema Validation at Boundaries).
 */
@Component
@Order(0)
public class IngestionBoundaryFilter extends OncePerRequestFilter {

  static final int MAX_BODY_BYTES = 64 * 1024;
  static final String INGEST_PATH = "/ingest/hl7v2";
  static final String EXPECTED_CONTENT_TYPE = "application/hl7-v2";

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !INGEST_PATH.equals(request.getRequestURI())
        || !"POST".equalsIgnoreCase(request.getMethod());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    String contentType = request.getContentType();
    if (!matchesExpectedContentType(contentType)) {
      writeError(
          response,
          HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
          "HTTP_UNSUPPORTED_MEDIA_TYPE",
          "Content-Type must be " + EXPECTED_CONTENT_TYPE + ".");
      return;
    }

    long declared = request.getContentLengthLong();
    if (declared > MAX_BODY_BYTES) {
      writeError(
          response,
          HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
          "HTTP_PAYLOAD_TOO_LARGE",
          "Request body exceeds the " + MAX_BODY_BYTES + " byte cap.");
      return;
    }

    chain.doFilter(request, response);
  }

  private static boolean matchesExpectedContentType(String contentType) {
    if (contentType == null) {
      return false;
    }
    int semi = contentType.indexOf(';');
    String base = (semi >= 0 ? contentType.substring(0, semi) : contentType).trim();
    return EXPECTED_CONTENT_TYPE.equalsIgnoreCase(base);
  }

  private static void writeError(
      HttpServletResponse response, int status, String code, String message) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    String correlationId = MDC.get("correlation_id");
    String body =
        "{\"error\":{\"code\":\""
            + code
            + "\",\"message\":\""
            + jsonEscape(message)
            + "\",\"correlationId\":"
            + (correlationId == null ? "null" : "\"" + jsonEscape(correlationId) + "\"")
            + "}}";
    response.getWriter().write(body);
  }

  private static String jsonEscape(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
