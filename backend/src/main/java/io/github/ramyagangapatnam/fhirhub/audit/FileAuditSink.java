package io.github.ramyagangapatnam.fhirhub.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ramyagangapatnam.fhirhub.observability.CorrelationIdFilter;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link AuditEventEmitter} that appends one JSON record per audit event to a JSONL file. Written
 * in {@link StandardOpenOption#APPEND} mode so concurrent writers fan their lines into the file
 * without truncating one another (POSIX append-mode semantics; modern Java honors the same on
 * Windows for the lengths we write here).
 *
 * <p>The schema is enforced by this class — callers pass only the domain-meaningful fields and the
 * emitter fills {@code auditId}, {@code schemaVersion}, {@code timestampUtc}, and {@code
 * correlationId} from MDC. See {@code contracts/audit-event.schema.json}.
 *
 * <p>Activated by setting {@code fhir-hub.audit.sink=file} — Spring Boot's
 * {@link ConditionalOnProperty} wins over the {@code NoOpAuditEventEmitter} fallback because both
 * are configured to participate in the same bean slot via {@code AuditEventEmitter}.
 *
 * <p>Principle II — append-only, distinct sink (NON-NEGOTIABLE).
 */
@Component
@ConditionalOnProperty(name = "fhir-hub.audit.sink", havingValue = "file")
public class FileAuditSink implements AuditEventEmitter {

  private static final Logger LOGGER = LoggerFactory.getLogger(FileAuditSink.class);
  private static final String SCHEMA_VERSION = "1";
  private static final String SYSTEM_ACTOR_IDENTITY = "system:ingestion";
  private static final OpenOption[] APPEND_OPTIONS = {
    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND
  };

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Path filePath;
  private final Object writeLock = new Object();

  public FileAuditSink(
      @Value("${fhir-hub.audit.file.path:var/audit/audit.log}") String configuredPath) {
    this.filePath = Paths.get(configuredPath);
  }

  @PostConstruct
  void ensureParentDirectoryExists() throws IOException {
    Path parent = filePath.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
  }

  @Override
  public void emit(
      String resourceType,
      String resourceId,
      String operation,
      String outcome,
      UUID correlationId) {
    Map<String, Object> event = buildEvent(resourceType, resourceId, operation, outcome, correlationId);
    String line;
    try {
      line = objectMapper.writeValueAsString(event) + "\n";
    } catch (JsonProcessingException ex) {
      LOGGER.error("audit.emit.serialize.error operation={} resource_type={}", operation, resourceType, ex);
      return;
    }
    try {
      synchronized (writeLock) {
        Files.writeString(filePath, line, StandardCharsets.UTF_8, APPEND_OPTIONS);
      }
    } catch (IOException ex) {
      LOGGER.error("audit.emit.write.error path={}", filePath, ex);
    }
  }

  private static Map<String, Object> buildEvent(
      String resourceType,
      String resourceId,
      String operation,
      String outcome,
      UUID correlationId) {
    Map<String, Object> actor = new LinkedHashMap<>();
    actor.put("type", "system");
    actor.put("identity", SYSTEM_ACTOR_IDENTITY);

    Map<String, Object> resource = new LinkedHashMap<>();
    resource.put("type", resourceType);
    resource.put("id", resourceId);

    UUID effectiveCorrelationId = correlationId != null ? correlationId : correlationIdFromMdc();

    Map<String, Object> event = new LinkedHashMap<>();
    event.put("auditId", UUID.randomUUID().toString());
    event.put("schemaVersion", SCHEMA_VERSION);
    event.put(
        "timestampUtc",
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.now(ZoneOffset.UTC)));
    event.put("actor", actor);
    event.put("correlationId", effectiveCorrelationId.toString());
    event.put("resource", resource);
    event.put("operation", operation);
    event.put("outcome", outcome);
    if ("failure".equals(outcome)) {
      Map<String, Object> failureReason = new LinkedHashMap<>();
      failureReason.put("code", "OPERATION_FAILED");
      failureReason.put("summary", operation + " on " + resourceType + " did not succeed.");
      event.put("failureReason", failureReason);
    }
    return event;
  }

  private static UUID correlationIdFromMdc() {
    String mdc = MDC.get(CorrelationIdFilter.MDC_KEY);
    if (mdc != null) {
      try {
        return UUID.fromString(mdc);
      } catch (IllegalArgumentException ignored) {
        // Filter restricts MDC to UUID-shaped values, but be defensive.
      }
    }
    return UUID.randomUUID();
  }
}
