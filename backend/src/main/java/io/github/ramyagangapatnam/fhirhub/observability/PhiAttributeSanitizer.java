package io.github.ramyagangapatnam.fhirhub.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Strips PHI from span attributes and event attributes at the export boundary, before traces leave
 * the JVM. Wraps a delegate {@link SpanExporter} and runs every string attribute value through
 * {@link PhiMasker}.
 *
 * <p>The spec / plan §6.1 refers to this component as the OpenTelemetry "SpanProcessor" — in
 * practice the only point at which span attributes can be safely rewritten before export is when
 * spans are flushed out of a {@link io.opentelemetry.sdk.trace.export.BatchSpanProcessor} into an
 * exporter. This class is therefore implemented as a sanitizing {@link SpanExporter} wrapping the
 * real exporter; functionally it performs the role the plan calls a "span processor".
 *
 * <p>Principle I (PHI Confidentiality — NON-NEGOTIABLE).
 */
public final class PhiAttributeSanitizer implements SpanExporter {

  private final SpanExporter delegate;

  public PhiAttributeSanitizer(SpanExporter delegate) {
    this.delegate = delegate;
  }

  @Override
  public CompletableResultCode export(Collection<SpanData> spans) {
    List<SpanData> sanitized = new ArrayList<>(spans.size());
    for (SpanData span : spans) {
      sanitized.add(new SanitizedSpanData(span));
    }
    return delegate.export(sanitized);
  }

  @Override
  public CompletableResultCode flush() {
    return delegate.flush();
  }

  @Override
  public CompletableResultCode shutdown() {
    return delegate.shutdown();
  }

  private static Attributes sanitize(Attributes attrs) {
    AttributesBuilder builder = Attributes.builder();
    attrs.forEach(
        (key, value) -> {
          if (value instanceof String s) {
            @SuppressWarnings("unchecked")
            AttributeKey<String> stringKey = (AttributeKey<String>) key;
            builder.put(stringKey, PhiMasker.mask(s));
          } else {
            putRaw(builder, key, value);
          }
        });
    return builder.build();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void putRaw(AttributesBuilder builder, AttributeKey key, Object value) {
    builder.put(key, value);
  }

  private static final class SanitizedSpanData extends DelegatingSpanData {

    private final Attributes sanitizedAttrs;
    private final List<EventData> sanitizedEvents;

    SanitizedSpanData(SpanData delegate) {
      super(delegate);
      this.sanitizedAttrs = sanitize(delegate.getAttributes());
      List<EventData> srcEvents = delegate.getEvents();
      this.sanitizedEvents = new ArrayList<>(srcEvents.size());
      for (EventData ev : srcEvents) {
        this.sanitizedEvents.add(
            EventData.create(
                ev.getEpochNanos(),
                PhiMasker.mask(ev.getName()),
                sanitize(ev.getAttributes()),
                ev.getTotalAttributeCount()));
      }
    }

    @Override
    public Attributes getAttributes() {
      return sanitizedAttrs;
    }

    @Override
    public List<EventData> getEvents() {
      return sanitizedEvents;
    }

    @Override
    public int getTotalAttributeCount() {
      return sanitizedAttrs.size();
    }
  }
}
