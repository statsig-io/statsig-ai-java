package com.statsig.ai.trace;

public class StatsigOTLPTraceExporterOptions {
    private static final String DEFAULT_OTEL_ENDPOINT = "https://api.statsig.com/otlp";

    private String endpoint = DEFAULT_OTEL_ENDPOINT;
    private String sdkKey = null;

    /**
     * Constructor for the StatsigOTLPTraceExporterOptions class.
     * @param sdkKey The SDK key to use for the exporter.
     */
    public StatsigOTLPTraceExporterOptions(String sdkKey) {
        this.sdkKey = sdkKey;
    }

    /**
     * Constructor for the StatsigOTLPTraceExporterOptions class.
     * @param sdkKey The SDK key to use for the exporter.
     * @param endpoint The endpoint to use for the exporter.
     */
    public StatsigOTLPTraceExporterOptions(String sdkKey, String endpoint) {
        this.sdkKey = sdkKey;
        this.endpoint = endpoint + "/v1/traces";
    }

    public String getSdkKey() {
        return sdkKey;
    }

    public String getEndpoint() {
        return endpoint;
    }
}
