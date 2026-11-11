package com.statsig.ai.trace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.statsig.StatsigUser;
import com.statsig.ai.trace.options.InitializeTracingOptions;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class OtelTest {
  private static final AttributeKey<String> DEPLOYMENT_ENVIRONMENT_NAME =
      AttributeKey.stringKey("deployment.environment.name");
  private static final AttributeKey<String> ENV = AttributeKey.stringKey("env");
  private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");

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
  public void resourceWithoutEnvironmentIsTheDefault() {
    assertEquals(
        Resource.getDefault(), Otel.buildResource(InitializeTracingOptions.builder().build()));
    assertEquals(
        Resource.getDefault(),
        Otel.buildResource(InitializeTracingOptions.builder().setEnvironment("").build()));
    assertEquals(Resource.getDefault(), Otel.buildResource(null));
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
