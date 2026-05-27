package io.github.ramyagangapatnam.fhirhub.audit;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fallback {@link AuditEventEmitter} used when no concrete sink is selected via {@code
 * fhir-hub.audit.sink}. {@link FileAuditSink} (T039) and the deferred S3 sink (T040) take
 * precedence — Spring's {@link ConditionalOnMissingBean} keeps this no-op from clashing with
 * them.
 *
 * <p>In normal demo runs the audit sink IS configured, so this fallback only fires in tests that
 * deliberately do not want a sink (e.g., the HTTP contract tests that assert nothing about the
 * audit JSONL).
 */
@Configuration
public class NoOpAuditEventEmitter {

  @Bean
  @ConditionalOnMissingBean(AuditEventEmitter.class)
  public AuditEventEmitter defaultAuditEventEmitter() {
    return new AuditEventEmitter() {
      @Override
      public void emit(
          String resourceType,
          String resourceId,
          String operation,
          String outcome,
          UUID correlationId) {
        // intentionally empty — the real audit path runs through FileAuditSink / S3AuditSink
      }
    };
  }
}
