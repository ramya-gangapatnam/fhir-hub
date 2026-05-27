package io.github.ramyagangapatnam.fhirhub.audit;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Placeholder {@link AuditEventEmitter} used until T039 lands the real file + S3 JSONL sinks. The
 * no-op MUST stay {@code @ConditionalOnMissingBean} so once T039 registers {@code FileAuditSink} /
 * {@code S3AuditSink} the production sink wins without any wiring change in T035.
 *
 * <p>Tracked deliberately as a stand-in, not a working audit path: until T039 lands, the audit
 * trail required by Principle II is incomplete. Tests that depend on the audit JSONL (T026) are
 * expected to fail by design until T039.
 */
@Configuration
public class NoOpAuditEventEmitter {

  @Bean
  @ConditionalOnMissingBean(AuditEventEmitter.class)
  public AuditEventEmitter noOpAuditEventEmitter() {
    return new AuditEventEmitter() {
      @Override
      public void emit(
          String resourceType,
          String resourceId,
          String operation,
          String outcome,
          UUID correlationId) {
        // intentionally empty — T039 replaces this with the JSONL sink
      }
    };
  }
}
