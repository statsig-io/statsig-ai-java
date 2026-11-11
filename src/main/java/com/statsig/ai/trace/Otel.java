package com.statsig.ai.trace;

import com.statsig.ai.trace.options.InitializeTracingOptions;
import com.statsig.ai.trace.options.StatsigOTLPTraceExporterOptions;
import com.statsig.ai.trace.singleton.OtelSingleton;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;

/**
 * Responsible for initializing the OTEL pipeline (exporter, processor, provider) and registering
 * the global instance via OtelSingleton.
 */
public class Otel {
  private static final String RESOURCE_ATTR_ENV = "env";

  public static OpenTelemetry initialize(InitializeTracingOptions options) {
    StatsigOTLPTraceExporterOptions exporterOptions = options.getExporterOptions();
    StatsigOTLPTraceExporter exporter = new StatsigOTLPTraceExporter(exporterOptions);

    SdkTracerProvider tracerProvider = buildTracerProvider(exporter, options);

    // Save to singleton for global lookup
    OtelSingleton.instantiate(tracerProvider, options.getEnvironment());

    // Build OpenTelemetry and register global
    OpenTelemetry openTelemetry =
        OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).buildAndRegisterGlobal();

    System.out.println(
        "[Statsig OTEL] Initialized with exporter: " + exporterOptions.getEndpoint());

    return openTelemetry;
  }

  static SdkTracerProvider buildTracerProvider(
      SpanExporter exporter, InitializeTracingOptions options) {
    return SdkTracerProvider.builder()
        .addSpanProcessor(new StatsigSpanProcessor(exporter))
        .setSampler(Sampler.alwaysOn())
        .setResource(buildResource(options))
        .build();
  }

  /**
   * The SDK's default resource, plus the environment from {@code options} as {@code
   * deployment.environment.name} and {@code env}.
   */
  static Resource buildResource(InitializeTracingOptions options) {
    String environment = options == null ? null : options.getEnvironment();
    if (environment == null || environment.isEmpty()) {
      return Resource.getDefault();
    }
    return Resource.getDefault()
        .merge(
            Resource.create(
                Attributes.of(
                    AttributeKey.stringKey(
                        StatsigTraceConventions.ATTR_DEPLOYMENT_ENVIRONMENT_NAME),
                    environment,
                    AttributeKey.stringKey(RESOURCE_ATTR_ENV),
                    environment)));
  }
}
