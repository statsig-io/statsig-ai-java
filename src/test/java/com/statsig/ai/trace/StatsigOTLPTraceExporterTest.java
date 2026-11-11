package com.statsig.ai.trace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.statsig.ai.trace.options.StatsigOTLPTraceExporterOptions;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class StatsigOTLPTraceExporterTest {
  private MockWebServer server;
  private SdkTracerProvider tracerProvider;

  @Before
  public void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
  }

  @After
  public void tearDown() throws IOException {
    if (tracerProvider != null) {
      tracerProvider.shutdown().join(5, TimeUnit.SECONDS);
      tracerProvider = null;
    }
    server.shutdown();
  }

  @Test
  public void exportsSpansToTracesEndpointWithApiKey() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/x-protobuf"));

    // Trailing slash on the DSN is stripped before /v1/traces is appended.
    StatsigOTLPTraceExporter exporter =
        new StatsigOTLPTraceExporter(
            new StatsigOTLPTraceExporterOptions(
                "secret-test-key", server.url("/otlp/").toString()));
    tracerProvider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();

    Span span = tracerProvider.get("exporter-test").spanBuilder("statsig-export-span").startSpan();
    span.end();
    CompletableResultCode flushed = tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
    assertTrue("Expected the export to succeed", flushed.isSuccess());

    RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
    assertNotNull("Expected an OTLP export request", request);
    assertEquals("POST", request.getMethod());
    assertEquals("/otlp/v1/traces", request.getPath());
    assertEquals("secret-test-key", request.getHeader("statsig-api-key"));
    assertEquals("application/x-protobuf", request.getHeader("Content-Type"));
    assertEquals("gzip", request.getHeader("Content-Encoding"));

    String body = gunzip(request.getBody().readByteArray());
    assertTrue("Expected the span name in the payload", body.contains("statsig-export-span"));
  }

  @Test
  public void optionsDefaultToStatsigTracesEndpoint() {
    assertEquals(
        "https://api.statsig.com/otlp/v1/traces",
        new StatsigOTLPTraceExporterOptions("secret-test-key").getEndpoint());
    assertEquals(
        "https://api.statsig.com/otlp/v1/traces",
        new StatsigOTLPTraceExporterOptions("secret-test-key", null).getEndpoint());
  }

  @Test
  public void optionsAppendTracesPathToCustomDsn() {
    assertEquals(
        "https://otel.example.com/otlp/v1/traces",
        new StatsigOTLPTraceExporterOptions("secret-test-key", "https://otel.example.com/otlp")
            .getEndpoint());
    assertEquals(
        "https://otel.example.com/otlp/v1/traces",
        new StatsigOTLPTraceExporterOptions("secret-test-key", "https://otel.example.com/otlp/")
            .getEndpoint());
  }

  @Test
  public void sdkKeyFallsBackToEnvironment() {
    assertEquals("secret-explicit", StatsigOTLPTraceExporter.resolveSdkKey("secret-explicit", "e"));
    assertEquals("secret-env", StatsigOTLPTraceExporter.resolveSdkKey(null, "secret-env"));
    assertEquals("secret-env", StatsigOTLPTraceExporter.resolveSdkKey("", "secret-env"));
  }

  @Test
  public void sdkKeyIsRequired() {
    assertThrows(
        IllegalArgumentException.class, () -> StatsigOTLPTraceExporter.resolveSdkKey(null, null));
    assertThrows(
        IllegalArgumentException.class, () -> StatsigOTLPTraceExporter.resolveSdkKey("", ""));
  }

  private static String gunzip(byte[] bytes) throws IOException {
    try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes));
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[1024];
      int len;
      while ((len = gzip.read(buffer)) != -1) {
        out.write(buffer, 0, len);
      }
      return out.toString(StandardCharsets.ISO_8859_1.name());
    }
  }
}
