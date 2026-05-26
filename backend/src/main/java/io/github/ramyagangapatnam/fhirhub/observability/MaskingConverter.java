package io.github.ramyagangapatnam.fhirhub.observability;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback message converter that applies {@link PhiMasker} to the formatted log message at the
 * emission boundary. Registered as the {@code %msg} / {@code %m} pattern converter in
 * {@code logback-spring.xml}, so every log line written by every appender is masked before it
 * leaves the JVM.
 *
 * <p>Principle I (PHI Confidentiality in Logs — NON-NEGOTIABLE): redaction MUST occur at the
 * emission boundary, never as a downstream cleanup pass.
 */
public class MaskingConverter extends MessageConverter {

  @Override
  public String convert(ILoggingEvent event) {
    return PhiMasker.mask(super.convert(event));
  }
}
