package com.statsig.ai.trace;

import static com.statsig.ai.trace.GenAISpans.startWorkflow;
import static com.statsig.ai.trace.GenAISpans.startWorkflowCallable;
import static com.statsig.ai.trace.GenAISpans.wrap;
import static com.statsig.ai.trace.GenAISpans.wrapCallable;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openai.client.OpenAIClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import com.statsig.StatsigUser;
import com.statsig.ai.instrumentation.openai.OpenAIWrapper;
import com.statsig.ai.trace.options.WrapOptions;
import com.statsig.ai.trace.singleton.OtelSingleton;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class GenAISpansTest {
  private static final AttributeKey<String> SPAN_TYPE = AttributeKey.stringKey("statsig.span.type");
  private static final AttributeKey<String> GEN_AI_SPAN_TYPE =
      AttributeKey.stringKey("statsig.span.gen_ai.type");
  private static final AttributeKey<String> LLM_ROOT =
      AttributeKey.stringKey("statsig.span.llm_root");
  private static final AttributeKey<String> WORKFLOW_NAME =
      AttributeKey.stringKey("statsig.gen_ai.workflow.name");
  private static final AttributeKey<String> TOOL_NAME =
      AttributeKey.stringKey("statsig.gen_ai.tool.name");
  private static final AttributeKey<String> TOOL_TYPE = AttributeKey.stringKey("gen_ai.tool.type");
  private static final AttributeKey<String> USER_ID = AttributeKey.stringKey("statsig.user_id");
  private static final AttributeKey<String> CUSTOM_IDS =
      AttributeKey.stringKey("statsig.custom_ids");
  private static final AttributeKey<String> ENVIRONMENT =
      AttributeKey.stringKey("statsig.environment");
  private static final AttributeKey<String> PROMPT_NAME =
      AttributeKey.stringKey("statsig.llm.prompt_name");
  private static final AttributeKey<String> PROMPT_VERSION =
      AttributeKey.stringKey("statsig.llm.prompt_version");
  private static final AttributeKey<String> PROMPT_VERSION_ID =
      AttributeKey.stringKey("statsig.llm.prompt_version_id");
  // The mocked params have no request body, so no model.
  private static final String MODEL_SPAN = "chat unknown";

  private InMemorySpanExporter exporter;
  private SdkTracerProvider tracerProvider;
  private SpanTelemetry.SpanEventLogger previousEventLogger;
  private List<LoggedEvent> events;
  private ChatCompletionCreateParams params;
  private OpenAIClient openai;

  @Before
  public void setUp() throws Exception {
    exporter = InMemorySpanExporter.create();
    tracerProvider =
        SdkTracerProvider.builder()
            .addSpanProcessor(new StatsigSpanProcessor(SimpleSpanProcessor.create(exporter)))
            .build();
    resetOtelSingleton();
    OtelSingleton.instantiate(tracerProvider);

    events = new ArrayList<LoggedEvent>();
    previousEventLogger = GenAISpans.eventLogger;
    GenAISpans.eventLogger =
        (user, name, metadata) -> events.add(new LoggedEvent(user, name, metadata));

    params = mock(ChatCompletionCreateParams.class);
    openai = OpenAIWrapper.wrap(mockOpenAIClient(params));
  }

  @After
  public void tearDown() throws Exception {
    GenAISpans.eventLogger = previousEventLogger;
    tracerProvider.shutdown().join(5, TimeUnit.SECONDS);
    resetOtelSingleton();
  }

  // ----------------------------------------------------------------------
  // Span names and attributes
  // ----------------------------------------------------------------------

  @Test
  public void toolSpanRecordsToolMetadataAndUserAttributes() {
    StatsigUser user =
        new StatsigUser.Builder()
            .setUserID("user-123")
            .setCustomIDs(Collections.singletonMap("team", "infra"))
            .build();

    Supplier<String> wrapped =
        wrap(
            WrapOptions.tool("search")
                .setToolType("retrieval")
                .setUser(user)
                .setAttribute("custom.attr", "provided")
                .setAttribute("custom.count", 3)
                .build(),
            () -> "ok");

    assertEquals("ok", wrapped.get());

    SpanData span = onlySpan();
    assertEquals("execute_tool", span.getName());
    assertEquals(SpanKind.INTERNAL, span.getKind());
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    Attributes attrs = span.getAttributes();
    assertEquals("gen_ai", attrs.get(SPAN_TYPE));
    assertEquals("tool", attrs.get(GEN_AI_SPAN_TYPE));
    assertEquals("search", attrs.get(TOOL_NAME));
    assertEquals("retrieval", attrs.get(TOOL_TYPE));
    assertEquals("provided", attrs.get(AttributeKey.stringKey("custom.attr")));
    assertEquals(Long.valueOf(3), attrs.get(AttributeKey.longKey("custom.count")));
    assertEquals("user-123", attrs.get(USER_ID));
    assertEquals("{\"team\":\"infra\"}", attrs.get(CUSTOM_IDS));
    assertNull(attrs.get(WORKFLOW_NAME));
    assertNull(attrs.get(LLM_ROOT));

    LoggedEvent event = onlyEvent();
    assertEquals("execute_tool", event.name);
    assertEquals("statsig-ai-openai-wrapper", event.user.getUserID());
    assertEquals("search", event.metadata.get("statsig.gen_ai.tool.name"));
    assertEquals("retrieval", event.metadata.get("gen_ai.tool.type"));
    assertEquals("tool", event.metadata.get("statsig.span.gen_ai.type"));
    assertEquals("provided", event.metadata.get("custom.attr"));
    assertEquals("3", event.metadata.get("custom.count"));
    assertEquals("user-123", event.metadata.get("statsig.user_id"));
    assertEquals("OK", event.metadata.get("span.status_code"));
    assertEquals(span.getSpanId(), event.metadata.get("span.span_id"));
  }

  @Test
  public void workflowSpanRecordsWorkflowMetadata() {
    assertEquals(
        "done",
        wrap(
                WrapOptions.workflow("embedding")
                    .setAttributes(Collections.singletonMap("custom.attr", "provided"))
                    .build(),
                () -> "done")
            .get());

    SpanData span = onlySpan();
    assertEquals("invoke_workflow", span.getName());
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    Attributes attrs = span.getAttributes();
    assertEquals("gen_ai", attrs.get(SPAN_TYPE));
    assertEquals("workflow", attrs.get(GEN_AI_SPAN_TYPE));
    assertEquals("embedding", attrs.get(WORKFLOW_NAME));
    assertEquals("provided", attrs.get(AttributeKey.stringKey("custom.attr")));
    assertNull(attrs.get(TOOL_NAME));
    assertNull(attrs.get(TOOL_TYPE));
    // Only startWorkflow marks a workflow span as the LLM root.
    assertNull(attrs.get(LLM_ROOT));
    assertNull(attrs.get(USER_ID));

    LoggedEvent event = onlyEvent();
    assertEquals("invoke_workflow", event.name);
    assertEquals("embedding", event.metadata.get("statsig.gen_ai.workflow.name"));
  }

  @Test
  public void setAttributesReplacesEarlierAttributes() {
    Map<String, Object> attributes = new LinkedHashMap<String, Object>();
    attributes.put("kept", "yes");
    attributes.put("dropped", null);
    WrapOptions options =
        WrapOptions.tool("search")
            .setAttribute("replaced", "earlier")
            .setAttributes(attributes)
            .setAttribute("removed", "soon")
            .setAttribute("removed", null)
            .build();

    assertEquals(Collections.singletonMap("kept", "yes"), options.getAttributes());
    assertNull(WrapOptions.tool("search").setToolType("").build().getToolType());
    assertNull(WrapOptions.workflow("turn").setActivityID("").build().getActivityID());
  }

  // ----------------------------------------------------------------------
  // Wrapper types
  // ----------------------------------------------------------------------

  @Test
  public void functionWrapperRunsASpanAndLogsAnEventPerCall() {
    Function<String, Integer> length =
        wrap(WrapOptions.tool("length").build(), (String query) -> query.length());

    assertEquals(Integer.valueOf(5), length.apply("hello"));
    assertEquals(Integer.valueOf(2), length.apply("hi"));

    List<SpanData> spans = exporter.getFinishedSpanItems();
    assertEquals(2, spans.size());
    assertEquals(2, events.size());
    for (int i = 0; i < 2; i++) {
      assertEquals("execute_tool", spans.get(i).getName());
      assertEquals(spans.get(i).getSpanId(), events.get(i).metadata.get("span.span_id"));
    }
    assertFalse(spans.get(0).getSpanId().equals(spans.get(1).getSpanId()));
  }

  @Test
  public void runnableWrapperRunsTheBodyInTheSpan() {
    AtomicReference<String> spanIDInside = new AtomicReference<String>();
    Runnable wrapped =
        wrap(
            WrapOptions.workflow("side-effect").build(),
            () -> {
              spanIDInside.set(Span.current().getSpanContext().getSpanId());
            });

    assertTrue(exporter.getFinishedSpanItems().isEmpty());
    wrapped.run();

    SpanData span = onlySpan();
    assertEquals("invoke_workflow", span.getName());
    assertEquals(span.getSpanId(), spanIDInside.get());
    assertEquals(1, events.size());
  }

  @Test
  public void callableWrapperRethrowsCheckedExceptionsUnchanged() {
    IOException error = new IOException("disk full");
    Callable<String> wrapped =
        wrapCallable(
            WrapOptions.tool("read").build(),
            () -> {
              throw error;
            });

    IOException thrown = assertThrows(IOException.class, wrapped::call);

    assertSame(error, thrown);
    SpanData span = onlySpan();
    assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
    assertEquals("disk full", span.getStatus().getDescription());
    assertEquals("java.io.IOException", onlyEvent().metadata.get("exception.type"));
  }

  // ----------------------------------------------------------------------
  // Errors
  // ----------------------------------------------------------------------

  @Test
  public void errorIsRecordedAndRethrown() {
    IllegalStateException error = new IllegalStateException("bad news");
    Supplier<String> wrapped =
        wrap(
            WrapOptions.workflow("embedding").build(),
            () -> {
              throw error;
            });

    IllegalStateException thrown = assertThrows(IllegalStateException.class, wrapped::get);

    assertSame(error, thrown);
    SpanData span = onlySpan();
    assertEquals("invoke_workflow", span.getName());
    assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
    assertEquals("bad news", span.getStatus().getDescription());
    assertEquals("embedding", span.getAttributes().get(WORKFLOW_NAME));
    assertEquals("workflow", span.getAttributes().get(GEN_AI_SPAN_TYPE));
    assertNotNull(exceptionEvent(span));

    LoggedEvent event = onlyEvent();
    assertEquals("invoke_workflow", event.name);
    assertEquals("embedding", event.metadata.get("statsig.gen_ai.workflow.name"));
    assertEquals("ERROR", event.metadata.get("span.status_code"));
    assertEquals("java.lang.IllegalStateException", event.metadata.get("exception.type"));
    assertEquals("bad news", event.metadata.get("exception.message"));
  }

  // ----------------------------------------------------------------------
  // CompletableFuture bodies
  // ----------------------------------------------------------------------

  @Test
  public void futureBodyEndsTheSpanWhenTheFutureCompletes() {
    CompletableFuture<String> pending = new CompletableFuture<String>();
    Supplier<CompletableFuture<String>> wrapped =
        wrap(WrapOptions.workflow("async-workflow").build(), () -> pending);

    CompletableFuture<String> returned = wrapped.get();

    assertNotSame(pending, returned);
    assertFalse(returned.isDone());
    assertTrue(exporter.getFinishedSpanItems().isEmpty());
    assertTrue(events.isEmpty());

    pending.complete("async-result");

    SpanData span = onlySpan();
    assertEquals("invoke_workflow", span.getName());
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    assertEquals("OK", onlyEvent().metadata.get("span.status_code"));
    assertEquals("async-result", returned.join());
  }

  @Test
  public void returnedFutureCompletesAfterTheSpanEnds() {
    CompletableFuture<String> pending = new CompletableFuture<String>();
    CompletableFuture<String> returned =
        wrap(WrapOptions.workflow("eval-record").build(), () -> pending).get();
    AtomicBoolean spanEndedFirst = new AtomicBoolean(false);
    // A caller waiting on the returned future, such as Eval() before it flushes spans, resumes
    // only after the span has ended.
    returned.thenRun(() -> spanEndedFirst.set(!exporter.getFinishedSpanItems().isEmpty()));

    pending.complete("done");

    assertTrue(spanEndedFirst.get());
    assertEquals("done", returned.join());
  }

  @Test
  public void futureBodyThatHasAlreadyCompletedEndsTheSpanRightAway() {
    CompletableFuture<String> returned =
        wrap(
                WrapOptions.tool("cached").build(),
                () -> CompletableFuture.completedFuture("cached-result"))
            .get();

    assertEquals("cached-result", returned.join());
    assertEquals(StatusCode.OK, onlySpan().getStatus().getStatusCode());
    assertEquals(1, events.size());
  }

  @Test
  public void futureBodyFailureIsRecorded() {
    CompletableFuture<String> source = new CompletableFuture<String>();
    IllegalStateException error = new IllegalStateException("bad news");
    CompletableFuture<String> returned =
        wrap(
                WrapOptions.workflow("embedding").build(),
                // Fails with a CompletionException that wraps error.
                () ->
                    source.<String>thenApply(
                        value -> {
                          throw error;
                        }))
            .get();

    source.complete("input");

    assertTrue(returned.isCompletedExceptionally());
    SpanData span = onlySpan();
    assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
    assertEquals("bad news", span.getStatus().getDescription());
    assertEquals(
        "java.lang.IllegalStateException",
        exceptionEvent(span).getAttributes().get(AttributeKey.stringKey("exception.type")));

    LoggedEvent event = onlyEvent();
    assertEquals("ERROR", event.metadata.get("span.status_code"));
    assertEquals("java.lang.IllegalStateException", event.metadata.get("exception.type"));
  }

  @Test
  public void futureBodyKeepsTheStatsigContextWhenItCompletesOnAnotherThread() throws Exception {
    StatsigUser user = new StatsigUser.Builder().setUserID("async-user-789").build();
    CompletableFuture<String> pending = new CompletableFuture<String>();
    CompletableFuture<String> returned =
        wrap(
                WrapOptions.workflow("async-workflow")
                    .setUser(user)
                    .setActivityID("async-activity-999")
                    .build(),
                () -> pending)
            .get();

    Thread completer = new Thread(() -> pending.complete("async-result"));
    completer.start();
    completer.join();

    assertEquals("async-result", returned.join());
    SpanData span = onlySpan();
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    assertEquals("async-user-789", span.getAttributes().get(USER_ID));
    assertEquals(
        "{\"statsig.activity_id\":\"async-activity-999\"}", span.getAttributes().get(CUSTOM_IDS));
    assertEquals(
        Collections.singletonMap("statsig.activity_id", "async-activity-999"),
        onlyEvent().user.getCustomIDs());
  }

  // ----------------------------------------------------------------------
  // User and activity ID
  // ----------------------------------------------------------------------

  @Test
  public void userAndActivityIDSetTheStatsigContext() {
    StatsigUser user =
        new StatsigUser.Builder()
            .setUserID("test-user-123")
            .setCustomIDs(Collections.singletonMap("sessionID", "session-456"))
            .build();
    AtomicReference<StatsigContextItem> inside = new AtomicReference<StatsigContextItem>();

    String result =
        wrap(
                WrapOptions.tool("test-tool").setUser(user).setActivityID("activity-789").build(),
                () -> {
                  inside.set(StatsigContextUtils.getActiveStatsigContext(Context.current()));
                  openai.chat().completions().create(params);
                  return "success";
                })
            .get();

    assertEquals("success", result);
    assertEquals("test-user-123", inside.get().getUser().getUserID());
    assertEquals("activity-789", inside.get().getActivityID());
    assertNull(StatsigContextUtils.getActiveStatsigContext(Context.current()));

    String customIDs = "{\"sessionID\":\"session-456\",\"statsig.activity_id\":\"activity-789\"}";
    SpanData tool = span("execute_tool");
    assertEquals(StatusCode.OK, tool.getStatus().getStatusCode());
    assertEquals("test-user-123", tool.getAttributes().get(USER_ID));
    assertEquals(customIDs, tool.getAttributes().get(CUSTOM_IDS));
    // The model call inside it is attributed to the same user.
    SpanData model = span(MODEL_SPAN);
    assertEquals("test-user-123", model.getAttributes().get(USER_ID));
    assertEquals(customIDs, model.getAttributes().get(CUSTOM_IDS));

    LoggedEvent event = onlyEvent();
    assertEquals("test-user-123", event.metadata.get("statsig.user_id"));
    assertEquals(customIDs, event.metadata.get("statsig.custom_ids"));
    assertEquals(
        Collections.singletonMap("statsig.activity_id", "activity-789"), event.user.getCustomIDs());
  }

  @Test
  public void activityIDWithoutAUser() {
    assertEquals(
        "done",
        wrap(
                WrapOptions.workflow("test-workflow").setActivityID("activity-only-123").build(),
                () -> "done")
            .get());

    Attributes attrs = onlySpan().getAttributes();
    assertNull(attrs.get(USER_ID));
    assertEquals("{\"statsig.activity_id\":\"activity-only-123\"}", attrs.get(CUSTOM_IDS));
  }

  @Test
  public void userWithoutAnActivityID() {
    StatsigUser user = new StatsigUser.Builder().setUserID("user-only-456").build();

    assertEquals(
        "result", wrap(WrapOptions.tool("test-tool").setUser(user).build(), () -> "result").get());

    Attributes attrs = onlySpan().getAttributes();
    assertEquals("user-only-456", attrs.get(USER_ID));
    assertNull(attrs.get(CUSTOM_IDS));
    assertTrue(
        onlyEvent().user.getCustomIDs() == null || onlyEvent().user.getCustomIDs().isEmpty());
  }

  // ----------------------------------------------------------------------
  // Environment and prompt attributes
  // ----------------------------------------------------------------------

  @Test
  public void spansGetTheEnvironmentPromptVersionAndUserFromTheContext() {
    StatsigUser user = new StatsigUser.Builder().setUserID("prompt-user").build();
    Context ctx =
        StatsigContextUtils.withPromptVersion(
            StatsigContextUtils.withUser(
                StatsigContextUtils.withStatsigEnvironment(Context.current(), "staging_canary"),
                user),
            "support_agent",
            "Version 3",
            "version-3");

    try (Scope ignored = ctx.makeCurrent()) {
      startWorkflow("agent-turn", () -> wrap(WrapOptions.tool("lookup").build(), () -> "ok").get());
    }

    for (String name : Arrays.asList("invoke_workflow", "execute_tool")) {
      Attributes attrs = span(name).getAttributes();
      assertEquals(name, "staging_canary", attrs.get(ENVIRONMENT));
      assertEquals(name, "support_agent", attrs.get(PROMPT_NAME));
      assertEquals(name, "Version 3", attrs.get(PROMPT_VERSION));
      assertEquals(name, "version-3", attrs.get(PROMPT_VERSION_ID));
      assertEquals(name, "prompt-user", attrs.get(USER_ID));

      Map<String, String> metadata = event(name).metadata;
      assertEquals(name, "staging_canary", metadata.get("statsig.environment"));
      assertEquals(name, "support_agent", metadata.get("statsig.llm.prompt_name"));
      assertEquals(name, "Version 3", metadata.get("statsig.llm.prompt_version"));
      assertEquals(name, "version-3", metadata.get("statsig.llm.prompt_version_id"));
      assertEquals(name, "prompt-user", metadata.get("statsig.user_id"));
    }
  }

  // ----------------------------------------------------------------------
  // Parenting and LLM root
  // ----------------------------------------------------------------------

  @Test
  public void onlyModelCallsOutsideAWorkflowAreTheLLMRoot() {
    Supplier<ChatCompletion> ask = () -> openai.chat().completions().create(params);

    ask.get();
    wrap(
            WrapOptions.workflow("agent-turn").build(),
            () -> {
              ask.get();
              wrap(WrapOptions.tool("lookup").build(), ask).get();
              ask.get();
            })
        .run();

    // In the order they ended: standalone, first round, in tool, tool, final round, workflow.
    List<SpanData> spans = exporter.getFinishedSpanItems();
    List<String> names = new ArrayList<String>();
    List<String> llmRoots = new ArrayList<String>();
    for (SpanData span : spans) {
      names.add(span.getName());
      llmRoots.add(span.getAttributes().get(LLM_ROOT));
    }
    assertEquals(
        Arrays.asList(
            MODEL_SPAN, MODEL_SPAN, MODEL_SPAN, "execute_tool", MODEL_SPAN, "invoke_workflow"),
        names);
    assertEquals(Arrays.asList("1", null, null, null, null, null), llmRoots);

    SpanData standalone = spans.get(0);
    SpanData workflow = spans.get(5);
    SpanData tool = spans.get(3);
    assertEquals(SpanId.getInvalid(), standalone.getParentSpanId());
    assertEquals(SpanId.getInvalid(), workflow.getParentSpanId());
    assertEquals(workflow.getSpanId(), spans.get(1).getParentSpanId());
    assertEquals(workflow.getSpanId(), tool.getParentSpanId());
    assertEquals(tool.getSpanId(), spans.get(2).getParentSpanId());
    assertEquals(workflow.getSpanId(), spans.get(4).getParentSpanId());
    assertEquals(workflow.getTraceId(), spans.get(2).getTraceId());
  }

  @Test
  public void modelCallInsideAToolSpanAloneIsTheLLMRoot() {
    wrap(WrapOptions.tool("lookup").build(), () -> openai.chat().completions().create(params))
        .get();

    SpanData model = span(MODEL_SPAN);
    SpanData tool = span("execute_tool");
    assertEquals("1", model.getAttributes().get(LLM_ROOT));
    assertNull(tool.getAttributes().get(LLM_ROOT));
    assertEquals(tool.getSpanId(), model.getParentSpanId());
  }

  // ----------------------------------------------------------------------
  // startWorkflow
  // ----------------------------------------------------------------------

  @Test
  public void startWorkflowIsTheLLMRootAndModelCallsInsideItAreNot() {
    String result =
        startWorkflow(
            "agent-turn",
            () -> {
              openai.chat().completions().create(params);
              wrap(
                      WrapOptions.tool("lookup").build(),
                      () -> openai.chat().completions().create(params))
                  .get();
              return "done";
            });

    assertEquals("done", result);
    List<SpanData> spans = exporter.getFinishedSpanItems();
    assertEquals(4, spans.size());
    SpanData workflow = span("invoke_workflow");
    assertEquals("1", workflow.getAttributes().get(LLM_ROOT));
    assertEquals("agent-turn", workflow.getAttributes().get(WORKFLOW_NAME));
    assertEquals(SpanId.getInvalid(), workflow.getParentSpanId());
    for (SpanData span : spans) {
      if (span != workflow) {
        assertNull(span.getName(), span.getAttributes().get(LLM_ROOT));
        assertEquals(workflow.getTraceId(), span.getTraceId());
      }
    }
    assertEquals("1", event("invoke_workflow").metadata.get("statsig.span.llm_root"));
  }

  @Test
  public void startWorkflowForwardsTheUserAndCustomAttributes() {
    StatsigUser user = new StatsigUser.Builder().setUserID("workflow-user").build();

    String result =
        startWorkflow(
            WrapOptions.workflow("outer-workflow")
                .setUser(user)
                .setAttribute("env.stage", "staging")
                .build(),
            () -> "done");

    assertEquals("done", result);
    SpanData span = onlySpan();
    assertEquals("invoke_workflow", span.getName());
    Attributes attrs = span.getAttributes();
    assertEquals("staging", attrs.get(AttributeKey.stringKey("env.stage")));
    assertEquals("1", attrs.get(LLM_ROOT));
    assertEquals("workflow", attrs.get(GEN_AI_SPAN_TYPE));
    assertEquals("workflow-user", attrs.get(USER_ID));

    LoggedEvent event = onlyEvent();
    assertEquals("invoke_workflow", event.name);
    assertEquals("outer-workflow", event.metadata.get("statsig.gen_ai.workflow.name"));
    assertEquals("staging", event.metadata.get("env.stage"));
  }

  @Test
  public void startWorkflowRunsARunnableNow() {
    List<String> calls = new ArrayList<String>();

    startWorkflow(
        "side-effect",
        () -> {
          calls.add("name");
        });
    startWorkflow(
        WrapOptions.workflow("side-effect").build(),
        () -> {
          calls.add("options");
        });

    assertEquals(Arrays.asList("name", "options"), calls);
    List<SpanData> spans = exporter.getFinishedSpanItems();
    assertEquals(2, spans.size());
    for (SpanData span : spans) {
      assertEquals("1", span.getAttributes().get(LLM_ROOT));
    }
  }

  @Test
  public void startWorkflowCallableRethrowsCheckedExceptionsUnchanged() throws Exception {
    IOException error = new IOException("disk full");

    assertEquals("ok", startWorkflowCallable("agent-turn", () -> "ok"));
    IOException thrown =
        assertThrows(
            IOException.class,
            () ->
                startWorkflowCallable(
                    WrapOptions.workflow("agent-turn").build(),
                    () -> {
                      throw error;
                    }));

    assertSame(error, thrown);
    List<SpanData> spans = exporter.getFinishedSpanItems();
    assertEquals(StatusCode.OK, spans.get(0).getStatus().getStatusCode());
    assertEquals(StatusCode.ERROR, spans.get(1).getStatus().getStatusCode());
    assertEquals("1", spans.get(1).getAttributes().get(LLM_ROOT));
  }

  @Test
  public void startWorkflowRejectsToolOptions() {
    WrapOptions tool = WrapOptions.tool("lookup").build();

    assertThrows(IllegalArgumentException.class, () -> startWorkflow(tool, () -> "never"));
    assertThrows(IllegalArgumentException.class, () -> startWorkflowCallable(tool, () -> "never"));
    assertTrue(exporter.getFinishedSpanItems().isEmpty());
  }

  // ----------------------------------------------------------------------
  // Overloads
  // ----------------------------------------------------------------------

  @Test
  public void inlineLambdasCompileAgainstEveryOverload() throws Exception {
    WrapOptions tool = WrapOptions.tool("tool").build();
    WrapOptions workflow = WrapOptions.workflow("workflow").build();
    List<String> calls = new ArrayList<String>();

    // A lambda that returns a value picks Supplier, and one that returns nothing picks Runnable.
    Supplier<String> supplier = wrap(tool, () -> "value");
    Supplier<ChatCompletion> modelCall =
        wrap(tool, () -> openai.chat().completions().create(params));
    Supplier<Boolean> valueReturningCall = wrap(tool, () -> calls.add("supplier"));
    Supplier<CompletableFuture<String>> future =
        wrap(tool, () -> CompletableFuture.completedFuture("future"));
    Runnable runnable = wrap(tool, () -> calls.clear());
    Runnable block =
        wrap(
            tool,
            () -> {
              calls.add("runnable");
            });
    Function<String, Integer> function = wrap(tool, query -> query.length());
    Function<String, Integer> methodReference = wrap(tool, String::length);
    Callable<String> callable = wrapCallable(tool, () -> "callable");

    assertEquals("value", supplier.get());
    assertNull(modelCall.get());
    assertTrue(valueReturningCall.get());
    assertEquals("future", future.get().join());
    runnable.run();
    block.run();
    assertEquals(Collections.singletonList("runnable"), calls);
    assertEquals(Integer.valueOf(5), function.apply("hello"));
    assertEquals(Integer.valueOf(2), methodReference.apply("hi"));
    assertEquals("callable", callable.call());

    String started = startWorkflow("workflow", () -> "started");
    startWorkflow(
        "workflow",
        () -> {
          calls.add("started");
        });
    String startedWithOptions = startWorkflow(workflow, () -> "options");
    startWorkflow(workflow, () -> calls.clear());
    String startedCallable = startWorkflowCallable("workflow", () -> "callable-started");
    String startedCallableWithOptions = startWorkflowCallable(workflow, () -> "callable-options");

    assertEquals("started", started);
    assertEquals("options", startedWithOptions);
    assertEquals("callable-started", startedCallable);
    assertEquals("callable-options", startedCallableWithOptions);
    assertTrue(calls.isEmpty());
  }

  // ----------------------------------------------------------------------
  // Helpers
  // ----------------------------------------------------------------------

  private SpanData onlySpan() {
    List<SpanData> spans = exporter.getFinishedSpanItems();
    assertEquals(1, spans.size());
    return spans.get(0);
  }

  private SpanData span(String name) {
    for (SpanData span : exporter.getFinishedSpanItems()) {
      if (span.getName().equals(name)) {
        return span;
      }
    }
    throw new AssertionError("No span named " + name);
  }

  private LoggedEvent onlyEvent() {
    assertEquals(1, events.size());
    return events.get(0);
  }

  private LoggedEvent event(String name) {
    for (LoggedEvent event : events) {
      if (event.name.equals(name)) {
        return event;
      }
    }
    throw new AssertionError("No event for " + name);
  }

  private static EventData exceptionEvent(SpanData span) {
    for (EventData event : span.getEvents()) {
      if (event.getName().equals("exception")) {
        return event;
      }
    }
    return null;
  }

  private static OpenAIClient mockOpenAIClient(ChatCompletionCreateParams params) {
    OpenAIClient client = mock(OpenAIClient.class);
    ChatService chatService = mock(ChatService.class);
    ChatCompletionService chatCompletionService = mock(ChatCompletionService.class);
    when(client.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(chatCompletionService);
    when(chatCompletionService.create(params)).thenReturn(null);
    return client;
  }

  private static void resetOtelSingleton() throws Exception {
    Field instanceField = OtelSingleton.class.getDeclaredField("instance");
    instanceField.setAccessible(true);
    instanceField.set(null, null);
  }

  private static final class LoggedEvent {
    final StatsigUser user;
    final String name;
    final Map<String, String> metadata;

    LoggedEvent(StatsigUser user, String name, Map<String, String> metadata) {
      this.user = user;
      this.name = name;
      this.metadata = new HashMap<String, String>(metadata);
    }
  }
}
