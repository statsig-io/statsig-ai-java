package com.statsig.ai.trace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import com.statsig.StatsigUser;
import com.statsig.ai.trace.options.InitializeTracingOptions;
import com.statsig.ai.trace.options.StatsigOTLPTraceExporterOptions;
import com.statsig.ai.trace.singleton.OtelSingleton;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class OtelTest {
  private static final AttributeKey<String> DEPLOYMENT_ENVIRONMENT_NAME =
      AttributeKey.stringKey("deployment.environment.name");
  private static final AttributeKey<String> ENV = AttributeKey.stringKey("env");
  private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");
  private static final AttributeKey<String> SERVICE_VERSION =
      AttributeKey.stringKey("service.version");

  @Before
  @After
  public void reset() throws Exception {
    GlobalOpenTelemetry.resetForTest();
    Field instanceField = OtelSingleton.class.getDeclaredField("instance");
    instanceField.setAccessible(true);
    instanceField.set(null, null);
  }

  @Test
  public void resourceRecordsTheEnvironment() {
    Resource resource =
        Otel.buildResource(InitializeTracingOptions.builder().setEnvironment("staging").build());

    assertEquals("staging", resource.getAttribute(DEPLOYMENT_ENVIRONMENT_NAME));
    assertEquals("staging", resource.getAttribute(ENV));
    // The SDK's default attributes are kept.
    assertEquals(
        Resource.getDefault().getAttribute(SERVICE_NAME), resource.getAttribute(SERVICE_NAME));
  }

  @Test
  public void resourceRecordsTheServiceNameAndVersion() {
    Resource resource =
        Otel.buildResource(
            InitializeTracingOptions.builder()
                .setServiceName("checkout")
                .setVersion("1.2.3")
                .setEnvironment("staging")
                .build());

    assertEquals("checkout", resource.getAttribute(SERVICE_NAME));
    assertEquals("1.2.3", resource.getAttribute(SERVICE_VERSION));
    assertEquals("staging", resource.getAttribute(DEPLOYMENT_ENVIRONMENT_NAME));
  }

  @Test
  public void resourceServiceNameFallsBackToEnvironmentVariables() {
    Resource resource =
        Otel.buildResource(
            StatsigResourceAttributes.create(
                null, null, null, StatsigResourceAttributesTest.env(null, "from-service-name")));

    assertEquals("from-service-name", resource.getAttribute(SERVICE_NAME));
    assertNull(resource.getAttribute(SERVICE_VERSION));
  }

  @Test
  public void resourceWithoutAttributesIsTheDefault() {
    StatsigResourceAttributes none =
        StatsigResourceAttributes.create(
            null, null, "", StatsigResourceAttributesTest.env(null, null));
    assertEquals(Resource.getDefault(), Otel.buildResource(none));
  }

  @Test
  public void initializeDoesNotRegisterTheGlobalOpenTelemetryByDefault() {
    OpenTelemetry openTelemetry =
        Otel.initialize(
            InitializeTracingOptions.builder()
                .setExporterOptions(new StatsigOTLPTraceExporterOptions("secret-test"))
                .setServiceName("checkout")
                .build());
    try {
      // Nothing was registered, so the app can still set its own.
      GlobalOpenTelemetry.set(OpenTelemetry.noop());
      assertEquals("checkout", OtelSingleton.getResourceAttributesStatic().getService());
    } finally {
      ((OpenTelemetrySdk) openTelemetry).getSdkTracerProvider().shutdown();
    }
  }

  @Test
  public void initializeRegistersTheGlobalOpenTelemetryWhenAskedTo() {
    OpenTelemetry openTelemetry =
        Otel.initialize(
            InitializeTracingOptions.builder()
                .setExporterOptions(new StatsigOTLPTraceExporterOptions("secret-test"))
                .setEnableGlobalTraceProviderRegistration(true)
                .build());
    try {
      assertThrows(
          IllegalStateException.class, () -> GlobalOpenTelemetry.set(OpenTelemetry.noop()));
    } finally {
      ((OpenTelemetrySdk) openTelemetry).getSdkTracerProvider().shutdown();
    }
  }

  @Test
  public void tracerProviderUsesResourceAndStatsigSpanProcessor() {
    InMemorySpanExporter exporter = InMemorySpanExporter.create();
    SdkTracerProvider provider =
        Otel.buildTracerProvider(
            exporter, InitializeTracingOptions.builder().setEnvironment("production").build());
    try {
      StatsigUser user = new StatsigUser.Builder().setUserID("otel-user").build();
      StatsigContextUtils.withStatsigEnvironment(
          "eval",
          () ->
              StatsigContextUtils.withStatsigUserContext(
                  user,
                  () -> provider.get("otel-test").spanBuilder("otel-span").startSpan().end()));
      provider.get("otel-test").spanBuilder("plain-span").startSpan().end();

      provider.forceFlush().join(5, TimeUnit.SECONDS);
      assertEquals(2, exporter.getFinishedSpanItems().size());

      SpanData stamped = exporter.getFinishedSpanItems().get(0);
      assertEquals("otel-span", stamped.getName());
      assertEquals("production", stamped.getResource().getAttribute(DEPLOYMENT_ENVIRONMENT_NAME));
      assertEquals("production", stamped.getResource().getAttribute(ENV));
      assertEquals(
          "otel-user", stamped.getAttributes().get(AttributeKey.stringKey("statsig.user_id")));
      assertEquals(
          "eval", stamped.getAttributes().get(AttributeKey.stringKey("statsig.environment")));

      SpanData plain = exporter.getFinishedSpanItems().get(1);
      assertEquals("production", plain.getResource().getAttribute(DEPLOYMENT_ENVIRONMENT_NAME));
      assertNull(plain.getAttributes().get(AttributeKey.stringKey("statsig.environment")));
    } finally {
      provider.shutdown().join(5, TimeUnit.SECONDS);
    }
  }
}
