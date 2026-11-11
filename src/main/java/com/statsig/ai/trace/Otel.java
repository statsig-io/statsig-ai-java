package com.statsig.ai.trace;

import com.statsig.ai.trace.options.InitializeTracingOptions;
import com.statsig.ai.trace.options.StatsigOTLPTraceExporterOptions;
import com.statsig.ai.trace.singleton.OtelSingleton;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;

/**
 * Responsible for initializing the OTEL pipeline (exporter, processor, provider) and registering
 * the global instance via OtelSingleton.
 */
public class Otel {
  public static OpenTelemetry initialize(InitializeTracingOptions options) {
    StatsigOTLPTraceExporterOptions exporterOptions = options.getExporterOptions();
    StatsigOTLPTraceExporter exporter = new StatsigOTLPTraceExporter(exporterOptions);

    SdkTracerProvider tracerProvider = buildTracerProvider(exporter);

    // Save to singleton for global lookup
    OtelSingleton.instantiate(tracerProvider);

    // Build OpenTelemetry and register global
    OpenTelemetry openTelemetry =
        OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).buildAndRegisterGlobal();

    System.out.println(
        "[Statsig OTEL] Initialized with exporter: " + exporterOptions.getEndpoint());

    return openTelemetry;
  }

  static SdkTracerProvider buildTracerProvider(SpanExporter exporter) {
    return SdkTracerProvider.builder()
        .addSpanProcessor(new StatsigSpanProcessor(exporter))
        .setSampler(Sampler.alwaysOn())
        .build();
  }
}
