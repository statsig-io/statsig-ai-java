package com.statsig.ai.trace;

import com.statsig.ai.trace.options.StatsigOTLPTraceExporterOptions;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;

/**
 * Exports spans to Statsig over OTLP/HTTP (protobuf, gzip) at {@code <dsn>/v1/traces},
 * authenticated with the {@code statsig-api-key} header.
 */
public class StatsigOTLPTraceExporter implements SpanExporter {
  static final String SDK_KEY_ENV_VAR = "STATSIG_SDK_KEY";
  static final String API_KEY_HEADER = "statsig-api-key";

  private final SpanExporter delegate;

  /**
   * @param options exporter options; when null, or when they carry no SDK key, the key is read from
   *     the {@code STATSIG_SDK_KEY} environment variable.
   * @throws IllegalArgumentException if no SDK key is available.
   */
  public StatsigOTLPTraceExporter(StatsigOTLPTraceExporterOptions options) {
    StatsigOTLPTraceExporterOptions resolved =
        options != null ? options : new StatsigOTLPTraceExporterOptions(null);
    String sdkKey = resolveSdkKey(resolved.getSdkKey(), System.getenv(SDK_KEY_ENV_VAR));
    this.delegate =
        OtlpHttpSpanExporter.builder()
            .setEndpoint(resolved.getEndpoint())
            .addHeader(API_KEY_HEADER, sdkKey)
            .setCompression("gzip")
            .build();
  }

  static String resolveSdkKey(String sdkKey, String envSdkKey) {
    if (sdkKey != null && !sdkKey.isEmpty()) {
      return sdkKey;
    }
    if (envSdkKey != null && !envSdkKey.isEmpty()) {
      return envSdkKey;
    }
    throw new IllegalArgumentException("Statsig SDK Key is required for StatsigOTLPTraceExporter");
  }

  @Override
  public CompletableResultCode export(Collection<SpanData> spans) {
    return delegate.export(spans);
  }

  @Override
  public CompletableResultCode flush() {
    return delegate.flush();
  }

  @Override
  public CompletableResultCode shutdown() {
    return delegate.shutdown();
  }
}
