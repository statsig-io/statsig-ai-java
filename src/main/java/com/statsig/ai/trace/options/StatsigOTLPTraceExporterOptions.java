package com.statsig.ai.trace.options;

public class StatsigOTLPTraceExporterOptions {
  private static final String DEFAULT_OTEL_ENDPOINT = "https://api.statsig.com/otlp";

  private String endpoint = DEFAULT_OTEL_ENDPOINT;
  private String sdkKey = null;

  /**
   * Constructor for the StatsigOTLPTraceExporterOptions class.
   *
   * @param sdkKey The SDK key to use for the exporter.
   */
  public StatsigOTLPTraceExporterOptions(String sdkKey) {
    this.sdkKey = sdkKey;
  }

  /**
   * Constructor for the StatsigOTLPTraceExporterOptions class.
   *
   * @param sdkKey The SDK key to use for the exporter.
   * @param customEndpoint The customized endpoint to use for the exporter.
   */
  public StatsigOTLPTraceExporterOptions(String sdkKey, String customEndpoint) {
    this.sdkKey = sdkKey;
    this.endpoint = customEndpoint + "/v1/traces";
  }

  public String getSdkKey() {
    return sdkKey;
  }

  public String getEndpoint() {
    return endpoint;
  }
}
