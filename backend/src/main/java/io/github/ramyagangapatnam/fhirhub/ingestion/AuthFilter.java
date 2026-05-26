package io.github.ramyagangapatnam.fhirhub.ingestion;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer-token authentication filter. The expected token is supplied at construction time and
 * never logged or echoed in any response body. Rejections produce the JSON error envelope from
 * plan.md §3.7 with the stable codes from contracts/error-codes.md.
 */
public final class AuthFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";

  private static final List<String> UNAUTHENTICATED_PATHS =
      List.of("/actuator/health", "/actuator/info");

  private final String expectedToken;

  public AuthFilter(String expectedToken) {
    this.expectedToken = expectedToken;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    for (String open : UNAUTHENTICATED_PATHS) {
      if (path.equals(open) || path.startsWith(open + "/")) {
        return true;
      }
    }
    return false;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    String header = request.getHeader("Authorization");
    if (header == null || header.isBlank()) {
      writeError(response, "HTTP_AUTH_MISSING_TOKEN", "Authorization header is required.");
      return;
    }
    if (!header.startsWith(BEARER_PREFIX)) {
      writeError(response, "HTTP_AUTH_INVALID_TOKEN", "Authorization scheme is not Bearer.");
      return;
    }

    String presented = header.substring(BEARER_PREFIX.length());
    if (!constantTimeEquals(presented, expectedToken)) {
      writeError(response, "HTTP_AUTH_INVALID_TOKEN", "Bearer token is not recognized.");
      return;
    }

    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(
            "demo-operator",
            null,
            Collections.singletonList(new SimpleGrantedAuthority("ROLE_OPERATOR")));
    SecurityContextHolder.getContext().setAuthentication(auth);
    try {
      chain.doFilter(request, response);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null) {
      return false;
    }
    byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
    byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
    if (aBytes.length != bBytes.length) {
      return false;
    }
    int diff = 0;
    for (int i = 0; i < aBytes.length; i++) {
      diff |= aBytes[i] ^ bBytes[i];
    }
    return diff == 0;
  }

  private static void writeError(HttpServletResponse response, String code, String message)
      throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
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
