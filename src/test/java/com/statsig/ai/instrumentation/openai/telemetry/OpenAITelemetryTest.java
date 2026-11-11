package com.statsig.ai.instrumentation.openai.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.statsig.StatsigUser;
import com.statsig.ai.trace.StatsigContextUtils;
import com.statsig.ai.trace.singleton.OtelSingleton;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class OpenAITelemetryTest {
  private InMemorySpanExporter spanExporter;
  private SdkTracerProvider tracerProvider;
  private List<Map<String, String>> events;
  private OpenAITelemetry telemetry;

  @Before
  public void setUp() throws Exception {
    spanExporter = InMemorySpanExporter.create();
    tracerProvider =
        SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
            .build();
    resetOtelSingleton();
    OtelSingleton.instantiate(tracerProvider);

    events = new ArrayList<Map<String, String>>();
    telemetry = new OpenAITelemetry((name, metadata) -> events.add(metadata));
  }

  @After
  public void tearDown() throws Exception {
    tracerProvider.shutdown().join(5, TimeUnit.SECONDS);
    resetOtelSingleton();
  }

  @Test
  public void genAIEventMetadataCarriesStatsigContextAndEnvironment() throws Throwable {
    StatsigUser user =
        new StatsigUser.Builder()
            .setUserID("event-user")
            .setCustomIDs(Collections.singletonMap("org", "acme"))
            .build();
    Context ctx =
        StatsigContextUtils.withStatsigContext(
            StatsigContextUtils.withStatsigEnvironment(Context.current(), "eval"),
            user,
            "activity-1");

    try (Scope ignored = ctx.makeCurrent()) {
      assertEquals("ok", telemetry.trace("openai.chat.completions", "chat", span -> "ok"));
    }

    String customIDs = "{\"org\":\"acme\",\"statsig.activity_id\":\"activity-1\"}";
    assertEquals(1, events.size());
    Map<String, String> metadata = events.get(0);
    assertEquals("event-user", metadata.get("statsig.user_id"));
    assertEquals(customIDs, metadata.get("statsig.custom_ids"));
    assertEquals("eval", metadata.get("statsig.environment"));

    SpanData span = spanExporter.getFinishedSpanItems().get(0);
    assertEquals("event-user", span.getAttributes().get(AttributeKey.stringKey("statsig.user_id")));
    assertEquals(customIDs, span.getAttributes().get(AttributeKey.stringKey("statsig.custom_ids")));
    assertEquals("eval", span.getAttributes().get(AttributeKey.stringKey("statsig.environment")));
  }

  @Test
  public void genAIEventMetadataHasNoStatsigContextOutsideOne() throws Throwable {
    telemetry.trace("openai.chat.completions", "chat", span -> "ok");

    assertEquals(1, events.size());
    Map<String, String> metadata = events.get(0);
    assertFalse(metadata.containsKey("statsig.user_id"));
    assertFalse(metadata.containsKey("statsig.custom_ids"));
    assertFalse(metadata.containsKey("statsig.environment"));
    assertFalse(metadata.containsKey("statsig.llm.prompt_name"));
    assertFalse(metadata.containsKey("statsig.llm.prompt_version"));
    assertFalse(metadata.containsKey("statsig.llm.prompt_version_id"));
  }

  @Test
  public void genAIEventMetadataCarriesThePromptVersion() throws Throwable {
    Context ctx =
        StatsigContextUtils.withPromptVersion(
            Context.current(), "support_bot", "Candidate B", "candidate-b");

    try (Scope ignored = ctx.makeCurrent()) {
      telemetry.trace("openai.chat.completions", "chat", span -> "ok");
    }

    assertEquals(1, events.size());
    Map<String, String> metadata = events.get(0);
    assertEquals("support_bot", metadata.get("statsig.llm.prompt_name"));
    assertEquals("Candidate B", metadata.get("statsig.llm.prompt_version"));
    assertEquals("candidate-b", metadata.get("statsig.llm.prompt_version_id"));

    SpanData span = spanExporter.getFinishedSpanItems().get(0);
    assertEquals(
        "support_bot", span.getAttributes().get(AttributeKey.stringKey("statsig.llm.prompt_name")));
    assertEquals(
        "Candidate B",
        span.getAttributes().get(AttributeKey.stringKey("statsig.llm.prompt_version")));
    assertEquals(
        "candidate-b",
        span.getAttributes().get(AttributeKey.stringKey("statsig.llm.prompt_version_id")));
  }

  @Test
  public void genAIEventMetadataHasOnlyThePromptNameWithoutAVersion() throws Throwable {
    Context ctx =
        StatsigContextUtils.withPromptVersion(Context.current(), "support_bot", null, null);

    try (Scope ignored = ctx.makeCurrent()) {
      telemetry.trace("openai.chat.completions", "chat", span -> "ok");
    }

    Map<String, String> metadata = events.get(0);
    assertEquals("support_bot", metadata.get("statsig.llm.prompt_name"));
    assertFalse(metadata.containsKey("statsig.llm.prompt_version"));
    assertFalse(metadata.containsKey("statsig.llm.prompt_version_id"));
  }

  @Test
  public void modelSpanJoinsAnIncomingTrace() throws Throwable {
    String traceID = "0af7651916cd43dd8448eb211c80319c";
    String spanID = "b7ad6b7169203331";
    Context ctx =
        StatsigContextUtils.withIncomingTraceContext(
            Context.current(),
            Collections.singletonMap("traceparent", "00-" + traceID + "-" + spanID + "-01"));

    try (Scope ignored = ctx.makeCurrent()) {
      telemetry.trace("openai.chat.completions", "chat", span -> "ok");
    }

    SpanData span = spanExporter.getFinishedSpanItems().get(0);
    assertEquals(traceID, span.getTraceId());
    assertEquals(spanID, span.getParentSpanId());
  }

  private static void resetOtelSingleton() throws Exception {
    Field instanceField = OtelSingleton.class.getDeclaredField("instance");
    instanceField.setAccessible(true);
    instanceField.set(null, null);
  }
}
