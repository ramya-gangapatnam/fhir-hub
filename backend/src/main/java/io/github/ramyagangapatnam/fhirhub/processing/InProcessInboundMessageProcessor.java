package io.github.ramyagangapatnam.fhirhub.processing;

import io.github.ramyagangapatnam.fhirhub.transform.MessageTransformationService;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Default {@link InboundMessageProcessor} implementation. Submits the transformation pipeline run
 * to a bounded {@link ThreadPoolTaskExecutor} so the HTTP request thread is released as soon as
 * {@code inbound_message} has been persisted (SC-001).
 *
 * <p>The seam invariants from plan.md §5 are enforced here:
 *
 * <ol>
 *   <li>Only the inbound message id crosses into the worker — the raw HL7 body is re-read from the
 *       DB by {@link MessageTransformationService#process(UUID)}.
 *   <li>The HTTP request thread's MDC snapshot is copied into the worker before it runs so log
 *       lines emitted during transformation still carry {@code correlation_id} (Principle VII).
 *   <li>The call returns immediately; the controller never blocks on transformation.
 * </ol>
 *
 * <p>The executor is configured with a small core pool (matches the demo's < 10 msg/s load) and a
 * bounded queue so a sudden burst surfaces as task-rejection rather than unbounded memory growth.
 * Rejections are logged with the message id and re-thrown — the inbound_message row stays in {@code
 * RECEIVED} and an operator can replay it via the Inspector once US2 lands.
 */
@Component
@ConditionalOnBean(MessageTransformationService.class)
public class InProcessInboundMessageProcessor implements InboundMessageProcessor {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(InProcessInboundMessageProcessor.class);

  private final MessageTransformationService transformation;
  private final ThreadPoolTaskExecutor executor;

  public InProcessInboundMessageProcessor(MessageTransformationService transformation) {
    this.transformation = transformation;
    this.executor = buildExecutor();
  }

  @Override
  public void enqueue(UUID inboundMessageId) {
    Map<String, String> capturedMdc = MDC.getCopyOfContextMap();
    LOGGER.info("processor.enqueue message_id={}", inboundMessageId);
    executor.execute(() -> runWithMdc(inboundMessageId, capturedMdc));
  }

  private void runWithMdc(UUID inboundMessageId, Map<String, String> capturedMdc) {
    Map<String, String> priorMdc = MDC.getCopyOfContextMap();
    try {
      if (capturedMdc != null) {
        MDC.setContextMap(capturedMdc);
      } else {
        MDC.clear();
      }
      transformation.process(inboundMessageId);
    } catch (RuntimeException ex) {
      // The transformation service folds known errors into FAILED status + audit; anything escaping
      // here is genuinely unexpected. Log the message id only (Principle I — no PHI).
      LOGGER.error("processor.transformation.error message_id={}", inboundMessageId, ex);
    } finally {
      if (priorMdc == null) {
        MDC.clear();
      } else {
        MDC.setContextMap(priorMdc);
      }
    }
  }

  private static ThreadPoolTaskExecutor buildExecutor() {
    ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
    pool.setCorePoolSize(2);
    pool.setMaxPoolSize(4);
    pool.setQueueCapacity(64);
    pool.setThreadNamePrefix("ingest-worker-");
    pool.setWaitForTasksToCompleteOnShutdown(true);
    pool.setAwaitTerminationSeconds(10);
    pool.initialize();
    return pool;
  }
}
