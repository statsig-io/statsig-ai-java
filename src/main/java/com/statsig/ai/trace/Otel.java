package com.statsig.ai.trace;

import com.statsig.ai.trace.options.InitializeTracingOptions;
import com.statsig.ai.trace.options.StatsigOTLPTraceExporterOptions;
import com.statsig.ai.trace.singleton.OtelSingleton;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.OpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;

/**
 * Responsible for initializing the OTEL pipeline (exporter, processor, provider) and storing it in
 * OtelSingleton. It's registered as the global OpenTelemetry only when the options ask for it.
 */
public class Otel {
  private static final String RESOURCE_ATTR_ENV = "env";
  // Stable in the OpenTelemetry semantic conventions, which the SDK doesn't depend on.
  private static final String RESOURCE_ATTR_SERVICE_NAME = "service.name";
  private static final String RESOURCE_ATTR_SERVICE_VERSION = "service.version";

  public static OpenTelemetry initialize(InitializeTracingOptions options) {
    StatsigOTLPTraceExporterOptions exporterOptions = options.getExporterOptions();
    StatsigOTLPTraceExporter exporter = new StatsigOTLPTraceExporter(exporterOptions);

    StatsigResourceAttributes resources = resourceAttributes(options);
    SdkTracerProvider tracerProvider = buildTracerProvider(exporter, resources);

    // Save to singleton for global lookup
    OtelSingleton.instantiate(tracerProvider, resources);

    OpenTelemetrySdkBuilder sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider);
    // Like Node, the global OpenTelemetry is only replaced when the app asks for it.
    OpenTelemetry openTelemetry =
        options.isGlobalTraceProviderRegistrationEnabled()
            ? sdk.buildAndRegisterGlobal()
            : sdk.build();

    System.out.println(
        "[Statsig OTEL] Initialized with exporter: " + exporterOptions.getEndpoint());

    return openTelemetry;
  }

  static SdkTracerProvider buildTracerProvider(
      SpanExporter exporter, InitializeTracingOptions options) {
    return buildTracerProvider(exporter, resourceAttributes(options));
  }

  static SdkTracerProvider buildTracerProvider(
      SpanExporter exporter, StatsigResourceAttributes resources) {
    return SdkTracerProvider.builder()
        .addSpanProcessor(new StatsigSpanProcessor(exporter))
        .setSampler(Sampler.alwaysOn())
        .setResource(buildResource(resources))
        .build();
  }

  /**
   * The SDK's default resource, plus the service name and version from {@code options} as {@code
   * service.name} and {@code service.version}, and the environment as {@code
   * deployment.environment.name} and {@code env}. See {@link StatsigResourceAttributes#create} for
   * the service name's fallbacks.
   */
  static Resource buildResource(InitializeTracingOptions options) {
    return buildResource(resourceAttributes(options));
  }

  static Resource buildResource(StatsigResourceAttributes resources) {
    AttributesBuilder attributes = Attributes.builder();
    putIfSet(attributes, RESOURCE_ATTR_SERVICE_NAME, resources.getService());
    putIfSet(attributes, RESOURCE_ATTR_SERVICE_VERSION, resources.getVersion());
    putIfSet(
        attributes,
        StatsigTraceConventions.ATTR_DEPLOYMENT_ENVIRONMENT_NAME,
        resources.getEnvironment());
    putIfSet(attributes, RESOURCE_ATTR_ENV, resources.getEnvironment());
    Attributes built = attributes.build();
    if (built.isEmpty()) {
      return Resource.getDefault();
    }
    return Resource.getDefault().merge(Resource.create(built));
  }

  private static StatsigResourceAttributes resourceAttributes(InitializeTracingOptions options) {
    if (options == null) {
      return StatsigResourceAttributes.create(null, null, null);
    }
    return StatsigResourceAttributes.create(
        options.getServiceName(), options.getVersion(), options.getEnvironment());
  }

  private static void putIfSet(AttributesBuilder attributes, String key, String value) {
    if (value != null) {
      attributes.put(AttributeKey.stringKey(key), value);
    }
  }
}
