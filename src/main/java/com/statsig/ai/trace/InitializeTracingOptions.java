package com.statsig.ai.trace;

/** Configuration for initializing OpenTelemetry tracing with Statsig defaults. */
public final class InitializeTracingOptions {

  private final StatsigOTLPTraceExporterOptions exporterOptions;

  private InitializeTracingOptions(Builder builder) {
    this.exporterOptions = builder.exporterOptions;
  }

  /** Returns the configured exporter options (if any). */
  public StatsigOTLPTraceExporterOptions getExporterOptions() {
    return exporterOptions;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private StatsigOTLPTraceExporterOptions exporterOptions;

    /** Sets the exporter options for OTLP trace exporting. */
    public Builder setExporterOptions(StatsigOTLPTraceExporterOptions exporterOptions) {
      this.exporterOptions = exporterOptions;
      return this;
    }

    public InitializeTracingOptions build() {
      return new InitializeTracingOptions(this);
    }
  }
}
