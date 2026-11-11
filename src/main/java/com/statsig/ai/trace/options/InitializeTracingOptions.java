package com.statsig.ai.trace.options;

/** Configuration for initializing OpenTelemetry tracing with Statsig defaults. */
public final class InitializeTracingOptions {

  private final StatsigOTLPTraceExporterOptions exporterOptions;
  private final String environment;

  private InitializeTracingOptions(Builder builder) {
    this.exporterOptions = builder.exporterOptions;
    this.environment = builder.environment;
  }

  /** Returns the configured exporter options (if any). */
  public StatsigOTLPTraceExporterOptions getExporterOptions() {
    return exporterOptions;
  }

  /** Returns the configured environment (if any). */
  public String getEnvironment() {
    return environment;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private StatsigOTLPTraceExporterOptions exporterOptions;
    private String environment;

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

    public InitializeTracingOptions build() {
      return new InitializeTracingOptions(this);
    }
  }
}
