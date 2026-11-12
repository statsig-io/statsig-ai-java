package com.statsig.ai.trace.options;

/** Configuration for initializing OpenTelemetry tracing with Statsig defaults. */
public final class InitializeTracingOptions {

  private final StatsigOTLPTraceExporterOptions exporterOptions;
  private final String environment;
  private final String serviceName;
  private final String version;
  private final boolean globalTraceProviderRegistration;

  private InitializeTracingOptions(Builder builder) {
    this.exporterOptions = builder.exporterOptions;
    this.environment = builder.environment;
    this.serviceName = builder.serviceName;
    this.version = builder.version;
    this.globalTraceProviderRegistration = builder.globalTraceProviderRegistration;
  }

  /** Returns the configured exporter options (if any). */
  public StatsigOTLPTraceExporterOptions getExporterOptions() {
    return exporterOptions;
  }

  /** Returns the configured environment (if any). */
  public String getEnvironment() {
    return environment;
  }

  /** Returns the configured service name (if any). */
  public String getServiceName() {
    return serviceName;
  }

  /** Returns the configured service version (if any). */
  public String getVersion() {
    return version;
  }

  /** Returns whether the tracer provider is registered as the global OpenTelemetry. */
  public boolean isGlobalTraceProviderRegistrationEnabled() {
    return globalTraceProviderRegistration;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private StatsigOTLPTraceExporterOptions exporterOptions;
    private String environment;
    private String serviceName;
    private String version;
    private boolean globalTraceProviderRegistration;

    /** Sets the exporter options for OTLP trace exporting. */
    public Builder setExporterOptions(StatsigOTLPTraceExporterOptions exporterOptions) {
      this.exporterOptions = exporterOptions;
      return this;
    }

    /**
     * Sets the environment recorded on the tracer provider's resource as {@code
     * deployment.environment.name} and {@code env}. Use {@code production} for live traffic. To tag
     * only some spans, run them inside {@code StatsigContextUtils.withStatsigEnvironment}.
     */
    public Builder setEnvironment(String environment) {
      this.environment = environment;
      return this;
    }

    /**
     * Sets the service name recorded on the tracer provider's resource as {@code service.name}.
     * Without one, the {@code OTEL_SERVICE_NAME} environment variable is used, then {@code
     * SERVICE_NAME}.
     */
    public Builder setServiceName(String serviceName) {
      this.serviceName = serviceName;
      return this;
    }

    /**
     * Sets the service version recorded on the tracer provider's resource as {@code
     * service.version}.
     */
    public Builder setVersion(String version) {
      this.version = version;
      return this;
    }

    /**
     * Sets whether to register the tracer provider as the global OpenTelemetry, so spans from other
     * instrumentation that uses {@code GlobalOpenTelemetry} are exported to Statsig too. Off by
     * default, so the SDK doesn't replace your app's own global OpenTelemetry.
     */
    public Builder setEnableGlobalTraceProviderRegistration(boolean enable) {
      this.globalTraceProviderRegistration = enable;
      return this;
    }

    public InitializeTracingOptions build() {
      return new InitializeTracingOptions(this);
    }
  }
}
