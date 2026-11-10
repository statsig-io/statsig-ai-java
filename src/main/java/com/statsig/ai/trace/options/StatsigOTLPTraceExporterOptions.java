package com.statsig.ai.trace.options;

public class StatsigOTLPTraceExporterOptions {
  private static final String DEFAULT_DSN = "https://api.statsig.com/otlp";
  private static final String TRACES_PATH = "/v1/traces";

  private final String endpoint;
  private final String sdkKey;

  /**
   * Constructor for the StatsigOTLPTraceExporterOptions class.
   *
   * @param sdkKey The SDK key to use for the exporter. When null, the exporter reads the {@code
   *     STATSIG_SDK_KEY} environment variable.
   */
  public StatsigOTLPTraceExporterOptions(String sdkKey) {
    this(sdkKey, null);
  }

  /**
   * Constructor for the StatsigOTLPTraceExporterOptions class.
   *
   * @param sdkKey The SDK key to use for the exporter. When null, the exporter reads the {@code
   *     STATSIG_SDK_KEY} environment variable.
   * @param customEndpoint The DSN to export to; {@code /v1/traces} is appended. Defaults to {@code
   *     https://api.statsig.com/otlp} when null or empty.
   */
  public StatsigOTLPTraceExporterOptions(String sdkKey, String customEndpoint) {
    this.sdkKey = sdkKey;
    this.endpoint = tracesEndpoint(customEndpoint);
  }

  public String getSdkKey() {
    return sdkKey;
  }

  /** The full traces URL, {@code <dsn>/v1/traces}. */
  public String getEndpoint() {
    return endpoint;
  }

  private static String tracesEndpoint(String dsn) {
    String base = dsn == null || dsn.isEmpty() ? DEFAULT_DSN : dsn;
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base + TRACES_PATH;
  }
}
