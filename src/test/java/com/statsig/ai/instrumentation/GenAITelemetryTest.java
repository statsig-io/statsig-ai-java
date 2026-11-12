package com.statsig.ai.instrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.statsig.StatsigUser;
import com.statsig.ai.instrumentation.openai.OpenAIWrapperConfig;
import com.statsig.ai.trace.StatsigContextUtils;
import com.statsig.ai.trace.StatsigTraceConventions;
import com.statsig.ai.trace.singleton.OtelSingleton;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class GenAITelemetryTest {
  private InMemorySpanExporter spanExporter;
  private SdkTracerProvider tracerProvider;
  private List<Map<String, String>> events;
  private List<StatsigUser> eventUsers;
  private GenAITelemetry telemetry;

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
    eventUsers = new ArrayList<StatsigUser>();
    telemetry = newTelemetry();
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
      assertEquals("ok", trace("chat gpt-4o"));
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
    trace("chat gpt-4o");

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
      trace("chat gpt-4o");
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
      trace("chat gpt-4o");
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
      trace("chat gpt-4o");
    }

    SpanData span = spanExporter.getFinishedSpanItems().get(0);
    assertEquals(traceID, span.getTraceId());
    assertEquals(spanID, span.getParentSpanId());
  }

  @Test
  public void genAIEventUserHasNoCustomIDsWithoutEnvironmentOrActivityID() throws Throwable {
    // A Statsig context without an activity ID adds none.
    StatsigContextUtils.withStatsigContext(
        new StatsigUser.Builder().setUserID("someone").build(),
        null,
        () -> traceOrFail("chat gpt-4o"));

    assertEquals(1, eventUsers.size());
    StatsigUser user = eventUsers.get(0);
    assertEquals("statsig-ai-openai-wrapper", user.getUserID());
    assertTrue(user.getCustomIDs() == null || user.getCustomIDs().isEmpty());
  }

  @Test
  public void genAIEventUserCarriesTheTracerEnvironmentAndActivityID() throws Throwable {
    resetOtelSingleton();
    OtelSingleton.instantiate(tracerProvider, "staging");
    telemetry = newTelemetry();

    StatsigContextUtils.withStatsigContext(null, "activity-1", () -> traceOrFail("chat gpt-4o"));
    traceOrFail("chat gpt-4o");

    assertEquals(2, eventUsers.size());
    Map<String, String> both = new HashMap<String, String>();
    both.put("environment", "staging");
    both.put("statsig.activity_id", "activity-1");
    assertEquals("statsig-ai-openai-wrapper", eventUsers.get(0).getUserID());
    assertEquals(both, eventUsers.get(0).getCustomIDs());
    // Outside the Statsig context, only the environment.
    assertEquals(
        Collections.singletonMap("environment", "staging"), eventUsers.get(1).getCustomIDs());
  }

  @Test
  public void modelCallOutsideAnotherRootIsTheLLMRoot() throws Throwable {
    trace("chat gpt-4o");
    // An empty root counts as none.
    try (Scope ignored =
        Context.current().with(StatsigTraceConventions.CTX_GEN_AI_ROOT, "").makeCurrent()) {
      trace("chat gpt-4o");
    }

    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(2, spans.size());
    for (SpanData span : spans) {
      assertEquals("1", span.getAttributes().get(AttributeKey.stringKey("statsig.span.llm_root")));
    }
    assertEquals("1", events.get(0).get("statsig.span.llm_root"));
    assertEquals("1", events.get(1).get("statsig.span.llm_root"));
  }

  @Test
  public void modelCallInsideAnotherRootIsNotTheLLMRoot() throws Throwable {
    try (Scope ignored =
        Context.current()
            .with(StatsigTraceConventions.CTX_GEN_AI_ROOT, "invoke_workflow")
            .makeCurrent()) {
      trace("chat gpt-4o");
    }

    SpanData span = spanExporter.getFinishedSpanItems().get(0);
    assertNull(span.getAttributes().get(AttributeKey.stringKey("statsig.span.llm_root")));
    assertFalse(events.get(0).containsKey("statsig.span.llm_root"));
  }

  @Test
  public void modelCallIsTheLLMRootForModelCallsInsideIt() throws Throwable {
    AtomicReference<String> rootInside = new AtomicReference<String>();

    telemetry.trace(
        "outer",
        Collections.<String, Object>emptyMap(),
        () -> {
          rootInside.set(Context.current().get(StatsigTraceConventions.CTX_GEN_AI_ROOT));
          return trace("inner");
        },
        result -> Collections.<String, Object>emptyMap());

    assertEquals("outer", rootInside.get());
    // The context outside the call is unchanged.
    assertNull(Context.current().get(StatsigTraceConventions.CTX_GEN_AI_ROOT));

    Map<String, SpanData> spans = new HashMap<String, SpanData>();
    for (SpanData span : spanExporter.getFinishedSpanItems()) {
      spans.put(span.getName(), span);
    }
    AttributeKey<String> llmRoot = AttributeKey.stringKey("statsig.span.llm_root");
    assertEquals("1", spans.get("outer").getAttributes().get(llmRoot));
    assertNull(spans.get("inner").getAttributes().get(llmRoot));
    assertEquals(spans.get("outer").getSpanId(), spans.get("inner").getParentSpanId());
  }

  @Test
  public void spanHasTheRequestAndResultAttributesAndTimeToFirstToken() throws Throwable {
    Map<String, Object> request = new LinkedHashMap<String, Object>();
    request.put("gen_ai.request.model", "gpt-4o");
    request.put("gen_ai.request.stop_sequences", Arrays.asList("END"));

    String result =
        telemetry.trace(
            "chat gpt-4o",
            request,
            () -> "ok",
            value -> Collections.singletonMap("gen_ai.response.id", "resp-" + value));

    assertEquals("ok", result);
    SpanData span = spanExporter.getFinishedSpanItems().get(0);
    assertEquals("chat gpt-4o", span.getName());
    assertEquals(SpanKind.CLIENT, span.getKind());
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    assertEquals("gen_ai", span.getAttributes().get(AttributeKey.stringKey("statsig.span.type")));
    assertEquals(
        "gpt-4o", span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model")));
    assertEquals("resp-ok", span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.id")));
    // Lists are recorded as JSON, with their length, as in Node.
    assertEquals(
        "[\"END\"]",
        span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.stop_sequences")));
    assertEquals(
        Long.valueOf(7),
        span.getAttributes().get(AttributeKey.longKey("gen_ai.request.stop_sequences_len")));
    assertNotNull(
        span.getAttributes()
            .get(AttributeKey.longKey("statsig.gen_ai.server.time_to_first_token_ms")));
    assertNull(
        span.getAttributes().get(AttributeKey.doubleKey("gen_ai.server.time_to_first_token")));

    Map<String, String> metadata = events.get(0);
    assertEquals("chat gpt-4o", metadata.get("span.name"));
    assertEquals("[\"END\"]", metadata.get("gen_ai.request.stop_sequences"));
    assertEquals("7", metadata.get("gen_ai.request.stop_sequences_len"));
    assertEquals("OK", metadata.get("span.status_code"));
    assertNotNull(metadata.get("statsig.gen_ai.server.time_to_first_token_ms"));
    assertTrue(Long.parseLong(metadata.get("duration")) >= 0);
  }

  @Test
  public void customAttributesAreSetAndTheWrapperAttributesWin() throws Throwable {
    Map<String, Object> custom = new LinkedHashMap<String, Object>();
    custom.put("app.team", "support");
    custom.put("gen_ai.request.model", "custom-model");
    custom.put("statsig.span.type", "custom");
    telemetry = newTelemetry(OpenAIWrapperConfig.builder().setCustomAttributes(custom).build());

    telemetry.trace(
        "chat gpt-4o",
        Collections.singletonMap("gen_ai.request.model", "gpt-4o"),
        () -> "ok",
        value -> Collections.<String, Object>emptyMap());

    SpanData span = spanExporter.getFinishedSpanItems().get(0);
    assertEquals("support", span.getAttributes().get(AttributeKey.stringKey("app.team")));
    assertEquals(
        "gpt-4o", span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model")));
    assertEquals("gen_ai", span.getAttributes().get(AttributeKey.stringKey("statsig.span.type")));
    assertEquals("support", events.get(0).get("app.team"));
    assertEquals("gpt-4o", events.get(0).get("gen_ai.request.model"));
  }

  @Test
  public void failedCallIsMarkedAsAnErrorAndRethrown() {
    IllegalStateException error = new IllegalStateException("rate limited");
    try {
      telemetry.trace(
          "chat gpt-4o",
          Collections.<String, Object>emptyMap(),
          () -> {
            throw error;
          },
          value -> Collections.<String, Object>emptyMap());
      fail("expected the call's exception");
    } catch (Throwable t) {
      assertSame(error, t);
    }

    SpanData span = spanExporter.getFinishedSpanItems().get(0);
    assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
    assertEquals("rate limited", span.getStatus().getDescription());
    assertNull(
        span.getAttributes()
            .get(AttributeKey.longKey("statsig.gen_ai.server.time_to_first_token_ms")));
    Map<String, String> metadata = events.get(0);
    assertEquals("ERROR", metadata.get("span.status_code"));
    assertEquals(IllegalStateException.class.getName(), metadata.get("exception.type"));
    assertEquals("rate limited", metadata.get("exception.message"));
  }

  @Test
  public void resultAttributesThatFailDoNotHideTheResult() throws Throwable {
    String result =
        telemetry.trace(
            "chat gpt-4o",
            Collections.<String, Object>emptyMap(),
            () -> "ok",
            value -> {
              throw new IllegalArgumentException("unexpected response");
            });

    assertEquals("ok", result);
    SpanData span = spanExporter.getFinishedSpanItems().get(0);
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    assertEquals(1, events.size());
  }

  private GenAITelemetry newTelemetry() {
    return newTelemetry(null);
  }

  private GenAITelemetry newTelemetry(OpenAIWrapperConfig config) {
    return new GenAITelemetry(
        "test",
        config,
        (user, name, metadata) -> {
          eventUsers.add(user);
          events.add(metadata);
        });
  }

  private String trace(String name) throws Throwable {
    return telemetry.trace(
        name,
        Collections.<String, Object>emptyMap(),
        () -> "ok",
        result -> Collections.<String, Object>emptyMap());
  }

  private void traceOrFail(String name) {
    try {
      trace(name);
    } catch (Throwable t) {
      throw new AssertionError(t);
    }
  }

  private static void resetOtelSingleton() throws Exception {
    Field instanceField = OtelSingleton.class.getDeclaredField("instance");
    instanceField.setAccessible(true);
    instanceField.set(null, null);
  }
}
