package io.github.ramyagangapatnam.fhirhub.config;

import io.github.ramyagangapatnam.fhirhub.observability.PhiAttributeSanitizer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the OpenTelemetry SDK with a PHI-sanitizing exporter on the trace pipeline.
 *
 * <p>Configuration ({@code fhir-hub.otel.*} or {@code OTEL_*} env vars):
 *
 * <ul>
 *   <li>{@code fhir-hub.otel.exporter} ({@code otlp} or {@code none}, default {@code none}) —
 *       choose the trace exporter. {@code none} is the sensible default for dev/tests so the app
 *       starts without a collector reachable.
 *   <li>{@code OTEL_EXPORTER_OTLP_ENDPOINT} — passed straight through to the OTLP gRPC exporter
 *       when {@code exporter = otlp}.
 *   <li>{@code fhir-hub.otel.service-name} (default {@code fhir-hub}) — resource attribute.
 * </ul>
 *
 * <p>Principle VII (Observability from Day One); Principle I (PHI sanitization on traces).
 */
@Configuration
public class ObservabilityConfig {

  private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");

  private final String exporterKind;
  private final String otlpEndpoint;
  private final String serviceName;

  private volatile OpenTelemetrySdk sdk;

  public ObservabilityConfig(
      @Value("${fhir-hub.otel.exporter:none}") String exporterKind,
      @Value("${fhir-hub.otel.endpoint:${OTEL_EXPORTER_OTLP_ENDPOINT:}}") String otlpEndpoint,
      @Value("${fhir-hub.otel.service-name:fhir-hub}") String serviceName) {
    this.exporterKind = exporterKind == null ? "none" : exporterKind.trim().toLowerCase();
    this.otlpEndpoint = otlpEndpoint == null ? "" : otlpEndpoint.trim();
    this.serviceName = serviceName == null || serviceName.isBlank() ? "fhir-hub" : serviceName;
  }

  @Bean
  public OpenTelemetry openTelemetry() {
    Resource resource =
        Resource.getDefault().merge(Resource.create(Attributes.of(SERVICE_NAME, serviceName)));

    var providerBuilder = SdkTracerProvider.builder().setResource(resource);

    SpanExporter rawExporter = buildExporter();
    if (rawExporter != null) {
      SpanExporter sanitized = new PhiAttributeSanitizer(rawExporter);
      providerBuilder.addSpanProcessor(BatchSpanProcessor.builder(sanitized).build());
    }

    this.sdk =
        OpenTelemetrySdk.builder()
            .setTracerProvider(providerBuilder.build())
            .setPropagators(ContextPropagators.noop())
            .build();
    return this.sdk;
  }

  @Bean
  public Tracer fhirHubTracer(OpenTelemetry openTelemetry) {
    return openTelemetry.getTracer("io.github.ramyagangapatnam.fhirhub");
  }

  @PreDestroy
  public void shutdown() {
    if (sdk != null) {
      sdk.getSdkTracerProvider().shutdown().join(5, TimeUnit.SECONDS);
    }
  }

  private SpanExporter buildExporter() {
    return switch (exporterKind) {
      case "otlp" -> {
        var builder = OtlpGrpcSpanExporter.builder();
        if (!otlpEndpoint.isEmpty()) {
          builder.setEndpoint(otlpEndpoint);
        }
        yield builder.build();
      }
      case "none", "" -> null;
      default ->
          throw new IllegalArgumentException(
              "Unsupported fhir-hub.otel.exporter value: " + exporterKind);
    };
  }
}
