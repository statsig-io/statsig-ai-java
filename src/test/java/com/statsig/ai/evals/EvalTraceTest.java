package com.statsig.ai.evals;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import com.statsig.ai.instrumentation.openai.OpenAIWrapper;
import com.statsig.ai.testutil.MockScrapiServer;
import com.statsig.ai.trace.StatsigContextUtils;
import com.statsig.ai.trace.StatsigSpanProcessor;
import com.statsig.ai.trace.singleton.OtelSingleton;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Eval's per-record traces, as in Node's "record traces" tests. */
public class EvalTraceTest {
  private static final String API_KEY = "test-console-api-key";
  private static final String SEND_RESULTS_PATH = "/console/v1/evals/send_results";
  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String WORKFLOW_SPAN = "invoke_workflow";
  private static final String MODEL_SPAN = "openai.chat.completions";
  private static final AttributeKey<String> SPAN_TYPE = AttributeKey.stringKey("statsig.span.type");
  private static final AttributeKey<String> GEN_AI_SPAN_TYPE =
      AttributeKey.stringKey("statsig.span.gen_ai.type");
  private static final AttributeKey<String> WORKFLOW_NAME =
      AttributeKey.stringKey("statsig.gen_ai.workflow.name");
  private static final AttributeKey<String> LLM_ROOT =
      AttributeKey.stringKey("statsig.span.llm_root");
  private static final AttributeKey<String> ENVIRONMENT =
      AttributeKey.stringKey("statsig.environment");
  private static final AttributeKey<String> EVAL_NAME = AttributeKey.stringKey("statsig.eval.name");
  private static final AttributeKey<String> EVAL_RUN_NAME =
      AttributeKey.stringKey("statsig.eval.run_name");
  private static final AttributeKey<Long> EVAL_RECORD_INDEX =
      AttributeKey.longKey("statsig.eval.record_index");
  private static final AttributeKey<String> EVAL_SCORER_NAME =
      AttributeKey.stringKey("statsig.eval.scorer_name");

  private MockScrapiServer scrapi;
  private InMemorySpanExporter exporter;
  private SdkTracerProvider tracerProvider;
  private ChatCompletionCreateParams params;
  private OpenAIClient openai;
  private ExecutorService otherThread;
  private PrintStream originalErr;
  private ByteArrayOutputStream stderr;

  @Before
  public void setUp() throws Exception {
    scrapi = MockScrapiServer.create().start();
    exporter = InMemorySpanExporter.create();
    // Exports only on flush, so finished spans show that the eval flushed.
    tracerProvider =
        SdkTracerProvider.builder()
            .addSpanProcessor(
                new StatsigSpanProcessor(
                    BatchSpanProcessor.builder(exporter)
                        .setScheduleDelay(1, TimeUnit.HOURS)
                        .build()))
            .build();
    resetOtelSingleton();
    OtelSingleton.instantiate(tracerProvider);

    params = mock(ChatCompletionCreateParams.class);
    openai = OpenAIWrapper.wrap(mockOpenAIClient(params));
    otherThread = Executors.newSingleThreadExecutor();

    originalErr = System.err;
    stderr = new ByteArrayOutputStream();
    System.setErr(new PrintStream(stderr, true));
  }

  @After
  public void tearDown() throws Exception {
    System.setErr(originalErr);
    otherThread.shutdownNow();
    tracerProvider.shutdown().join(5, TimeUnit.SECONDS);
    resetOtelSingleton();
    scrapi.close();
  }

  @Test
  public void givesEachRecordItsOwnTraceWithItsModelCallsAndScorersUnderTheRecordSpan()
      throws Exception {
    enqueueOk();
    Span outer = tracerProvider.get("test").spanBuilder("outer").startSpan();
    EvalResult<String, String, String> result;
    try (Scope ignored = outer.makeCurrent()) {
      result =
          local(
                  Eval.builder(
                          "greeting",
                          Arrays.asList(
                              EvalDataRecord.of("Foo", "Hello Foo"),
                              EvalDataRecord.of("Bar", "Hello Bar"),
                              EvalDataRecord.of("Baz", "Hello Baz")))
                      .task(
                          (input, hooks) -> {
                            openai.chat().completions().create(params);
                            return "Hello " + input;
                          })
                      .scorer(args -> args.getOutput().equals("Hello Foo")))
              .evalRunName("run-traces")
              .build()
              .run();
    } finally {
      outer.end();
    }

    // A record span, its model call and its scorer, for each record. The outer span ends after
    // the eval's flush, so it isn't exported.
    List<SpanData> spans = exporter.getFinishedSpanItems();
    assertEquals(9, spans.size());
    JsonNode sent = takeBody().get("results");
    Set<String> traceIDs = new HashSet<>();
    for (int i = 0; i < 3; i++) {
      EvalResultRecord<String, String, String> record = result.getResults().get(i);
      assertNotNull(record.getTraceID());
      assertNotEquals(outer.getSpanContext().getTraceId(), record.getTraceID());
      traceIDs.add(record.getTraceID());
      assertEquals(record.getTraceID(), sent.get(i).get("traceID").asText());
      assertEquals(record.getSpanID(), sent.get(i).get("spanID").asText());

      SpanData recordSpan = spanWithID(spans, record.getSpanID());
      assertEquals(record.getTraceID(), recordSpan.getTraceId());
      assertFalse(recordSpan.getParentSpanContext().isValid());
      assertEquals(WORKFLOW_SPAN, recordSpan.getName());
      assertEquals(StatusCode.OK, recordSpan.getStatus().getStatusCode());
      Attributes attributes = recordSpan.getAttributes();
      assertEquals("gen_ai", attributes.get(SPAN_TYPE));
      assertEquals("workflow", attributes.get(GEN_AI_SPAN_TYPE));
      assertEquals("greeting", attributes.get(WORKFLOW_NAME));
      assertEquals("1", attributes.get(LLM_ROOT));
      assertEquals("eval", attributes.get(ENVIRONMENT));
      assertEquals("greeting", attributes.get(EVAL_NAME));
      assertEquals("run-traces", attributes.get(EVAL_RUN_NAME));
      assertEquals(Long.valueOf(i), attributes.get(EVAL_RECORD_INDEX));

      List<SpanData> children = childrenOf(spans, recordSpan);
      assertEquals(2, children.size());
      SpanData model = named(children, MODEL_SPAN);
      assertEquals(record.getTraceID(), model.getTraceId());
      assertNull(model.getAttributes().get(LLM_ROOT));
      assertEquals("eval", model.getAttributes().get(ENVIRONMENT));
      SpanData scorer = named(children, WORKFLOW_SPAN);
      assertEquals("Grader", scorer.getAttributes().get(WORKFLOW_NAME));
      assertEquals("Grader", scorer.getAttributes().get(EVAL_SCORER_NAME));
      assertNull(scorer.getAttributes().get(LLM_ROOT));
      assertEquals("eval", scorer.getAttributes().get(ENVIRONMENT));
    }
    assertEquals(3, traceIDs.size());
  }

  @Test
  public void parentsAnAsyncTasksModelCallAndAnAsyncScorerUnderTheRecordSpan() throws Exception {
    enqueueOk();

    EvalResult<String, String, String> result =
        local(
                Eval.builder("greeting", Arrays.asList(EvalDataRecord.of("Foo", "Hello Foo")))
                    .asyncTask(
                        // Work handed to another thread joins the record's trace when the task
                        // passes the context along.
                        (input, hooks) ->
                            CompletableFuture.supplyAsync(
                                () -> {
                                  openai.chat().completions().create(params);
                                  return "Hello " + input;
                                },
                                Context.current().wrap(otherThread)))
                    .scorer(
                        "async",
                        args ->
                            CompletableFuture.supplyAsync(
                                () -> {
                                  pause(50);
                                  return args.getOutput().equals(args.getExpected());
                                },
                                otherThread)))
            .build()
            .run();

    EvalResultRecord<String, String, String> record = result.getResults().get(0);
    assertEquals(Collections.singletonMap("async", 1.0), record.getScores());
    List<SpanData> spans = exporter.getFinishedSpanItems();
    assertEquals(3, spans.size());
    SpanData recordSpan = spanWithID(spans, record.getSpanID());
    assertFalse(recordSpan.getParentSpanContext().isValid());

    SpanData model = named(spans, MODEL_SPAN);
    assertEquals(recordSpan.getSpanId(), model.getParentSpanId());
    SpanData scorer = spanWithAttribute(spans, EVAL_SCORER_NAME, "async");
    assertEquals(recordSpan.getSpanId(), scorer.getParentSpanId());
    // The scorer's span ends when its future completes, and the record's span after that.
    assertTrue(
        scorer.getEndEpochNanos() - scorer.getStartEpochNanos()
            >= TimeUnit.MILLISECONDS.toNanos(50));
    assertTrue(recordSpan.getEndEpochNanos() >= scorer.getEndEpochNanos());

    JsonNode sent = takeBody().get("results").get(0);
    assertEquals(record.getTraceID(), sent.get("traceID").asText());
    assertEquals(record.getSpanID(), sent.get("spanID").asText());
  }

  @Test
  public void marksTheRecordSpanAsFailedWhenTheTaskThrows() throws Exception {
    enqueueOk();

    EvalResult<String, String, String> result =
        local(
                Eval.builder("failing", Arrays.asList(EvalDataRecord.of("Boom", "Anything")))
                    .<String>task(
                        (input, hooks) -> {
                          throw new IllegalStateException("Task failed");
                        })
                    .scorer(args -> 1))
            .build()
            .run();

    EvalResultRecord<String, String, String> record = result.getResults().get(0);
    assertTrue(record.isError());
    assertNotNull(record.getTraceID());
    assertNotNull(record.getSpanID());
    // The scorer doesn't run, so the record span is the only one.
    List<SpanData> spans = exporter.getFinishedSpanItems();
    assertEquals(1, spans.size());
    SpanData recordSpan = spanWithID(spans, record.getSpanID());
    assertEquals(StatusCode.ERROR, recordSpan.getStatus().getStatusCode());
    assertEquals("Task failed", recordSpan.getStatus().getDescription());

    JsonNode sent = takeBody().get("results").get(0);
    assertEquals("[Error]", sent.get("output").asText());
    assertEquals(record.getTraceID(), sent.get("traceID").asText());
    assertEquals(record.getSpanID(), sent.get("spanID").asText());
  }

  @Test
  public void exportsTheSpansBeforeSendingTheResults() throws Exception {
    AtomicInteger exportedWhenSent = new AtomicInteger(-1);
    scrapi
        .getServer()
        .setDispatcher(
            new Dispatcher() {
              @Override
              public MockResponse dispatch(RecordedRequest request) {
                exportedWhenSent.set(exporter.getFinishedSpanItems().size());
                return new MockResponse().setResponseCode(200).setBody("{}");
              }
            });

    local(
            Eval.builder("greeting", greetings())
                .task((input, hooks) -> "Hello " + input)
                .scorer(args -> args.getOutput().equals(args.getExpected())))
        .build()
        .run();

    // A record span and a scorer span for each of the two records.
    assertEquals(4, exportedWhenSent.get());
  }

  @Test
  public void withoutTracingRunsInTheEvalEnvironmentWithNoTrace() throws Exception {
    resetOtelSingleton();
    enqueueOk();
    AtomicReference<Map<String, String>> taskEnvironment = new AtomicReference<>();
    AtomicReference<Map<String, String>> scorerEnvironment = new AtomicReference<>();
    AtomicReference<SpanContext> taskSpan = new AtomicReference<>();

    EvalResult<String, String, String> result =
        local(
                Eval.builder("greeting", greetings())
                    .task(
                        (input, hooks) -> {
                          taskEnvironment.set(
                              StatsigContextUtils.getEnvironmentSpanAttrs(Context.current()));
                          taskSpan.set(Span.current().getSpanContext());
                          return "Hello " + input;
                        })
                    .scorer(
                        args -> {
                          scorerEnvironment.set(
                              StatsigContextUtils.getEnvironmentSpanAttrs(Context.current()));
                          return 1;
                        }))
            .build()
            .run();

    Map<String, String> evalEnvironment = Collections.singletonMap("statsig.environment", "eval");
    assertEquals(evalEnvironment, taskEnvironment.get());
    assertEquals(evalEnvironment, scorerEnvironment.get());
    assertFalse(taskSpan.get().isValid());
    for (EvalResultRecord<String, String, String> record : result.getResults()) {
      assertNull(record.getTraceID());
      assertNull(record.getSpanID());
    }
    JsonNode sent = takeBody().get("results");
    for (JsonNode record : sent) {
      assertFalse(record.has("traceID"));
      assertFalse(record.has("spanID"));
    }
    tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
    assertTrue(exporter.getFinishedSpanItems().isEmpty());
  }

  @Test
  public void completesOnAOneThreadExecutor() throws Exception {
    enqueueOk();
    ExecutorService oneThread = Executors.newSingleThreadExecutor();
    try {
      CompletableFuture<EvalResult<String, String, String>> future =
          local(
                  Eval.builder(
                          "greeting",
                          Arrays.asList(
                              EvalDataRecord.of("Foo", "Hello Foo"),
                              EvalDataRecord.of("Bar", "Hello Bar"),
                              EvalDataRecord.of("Baz", "Hello Baz")))
                      .asyncTask(
                          (input, hooks) -> {
                            openai.chat().completions().create(params);
                            return CompletableFuture.supplyAsync(
                                () -> "Hello " + input, otherThread);
                          })
                      .scorer("exact", args -> args.getOutput().equals(args.getExpected()))
                      .scorer("async", args -> CompletableFuture.supplyAsync(() -> 1, otherThread)))
              .executor(oneThread)
              .build()
              .runAsync();

      EvalResult<String, String, String> result = future.get(10, TimeUnit.SECONDS);
      assertFalse(result.isError());
      for (EvalResultRecord<String, String, String> record : result.getResults()) {
        assertEquals(1.0, record.getScores().get("exact"), 0);
        assertEquals(1.0, record.getScores().get("async"), 0);
      }
      // A record span, a model call and two scorer spans for each record.
      List<SpanData> spans = exporter.getFinishedSpanItems();
      assertEquals(12, spans.size());
      for (EvalResultRecord<String, String, String> record : result.getResults()) {
        assertEquals(3, childrenOf(spans, spanWithID(spans, record.getSpanID())).size());
      }
      takeRequest();
    } finally {
      oneThread.shutdownNow();
    }
  }

  @Test
  public void flushDoesNotBlockTheCaller() throws Exception {
    enqueueOk();
    CompletableResultCode flush = new CompletableResultCode();
    useTracerProviderFlushingWith(flush);

    CompletableFuture<EvalResult<String, String, String>> future =
        local(
                Eval.builder("greeting", greetings())
                    .task((input, hooks) -> "Hello " + input)
                    .scorer(args -> args.getOutput().equals(args.getExpected())))
            .build()
            .runAsync();

    // runAsync returned while the flush is still pending, and the results wait for it.
    assertFalse(future.isDone());
    flush.succeed();
    assertEquals(2, future.get(5, TimeUnit.SECONDS).getResults().size());
    takeRequest();
  }

  @Test
  public void flushFailureIsLoggedAndTheResultsAreStillSent() throws Exception {
    enqueueOk();
    useTracerProviderFlushingWith(CompletableResultCode.ofFailure());

    EvalResult<String, String, String> result =
        local(
                Eval.builder("greeting", greetings())
                    .task((input, hooks) -> "Hello " + input)
                    .scorer(args -> args.getOutput().equals(args.getExpected())))
            .build()
            .run();

    assertNotNull(result.getResults().get(0).getTraceID());
    assertEquals(
        result.getResults().get(0).getTraceID(), takeBody().at("/results/0/traceID").asText());
    assertStderrContains("[StatsigAI] Failed to flush eval spans");
  }

  @Test
  public void flushTimeoutIsLoggedAndTheResultsAreStillSent() throws Exception {
    enqueueOk();
    // Never completes.
    useTracerProviderFlushingWith(new CompletableResultCode());

    EvalResult<String, String, String> result =
        local(
                Eval.builder("greeting", greetings())
                    .task((input, hooks) -> "Hello " + input)
                    .scorer(args -> args.getOutput().equals(args.getExpected())))
            .flushTimeout(100, TimeUnit.MILLISECONDS)
            .build()
            .run();

    assertEquals(2, result.getResults().size());
    takeRequest();
    assertStderrContains("[StatsigAI] Timed out flushing eval spans after 100 ms");
  }

  // ----------------------------------------------------------------------
  // Helpers
  // ----------------------------------------------------------------------

  private static List<EvalDataRecord<String, String>> greetings() {
    return Arrays.asList(
        EvalDataRecord.of("Foo", "Hello Foo"), EvalDataRecord.of("Bar", "Hello Bar"));
  }

  private <I, O, E> Eval.Builder<I, O, E> local(Eval.Builder<I, O, E> builder) {
    return builder.apiKey(API_KEY).endpoint(scrapi.url(SEND_RESULTS_PATH));
  }

  private void enqueueOk() {
    scrapi.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
  }

  private RecordedRequest takeRequest() throws InterruptedException {
    RecordedRequest request = scrapi.takeRequest(5, TimeUnit.SECONDS);
    assertNotNull("expected a send_results request", request);
    return request;
  }

  private JsonNode takeBody() throws Exception {
    return JSON.readTree(takeRequest().getBody().readUtf8());
  }

  private void assertStderrContains(String expected) {
    String output = stderr.toString();
    assertTrue(
        "stderr should contain: " + expected + "\nstderr was:\n" + output,
        output.contains(expected));
  }

  // Replaces the tracer provider with one that exports each span when it ends, and returns
  // flushResult from forceFlush. Shutting the old provider down stops its exporter, so this uses a
  // new one.
  private void useTracerProviderFlushingWith(CompletableResultCode flushResult) throws Exception {
    tracerProvider.shutdown().join(5, TimeUnit.SECONDS);
    resetOtelSingleton();
    SpanProcessor flushOnly =
        new SpanProcessor() {
          @Override
          public void onStart(Context parentContext, ReadWriteSpan span) {}

          @Override
          public boolean isStartRequired() {
            return false;
          }

          @Override
          public void onEnd(ReadableSpan span) {}

          @Override
          public boolean isEndRequired() {
            return false;
          }

          @Override
          public CompletableResultCode forceFlush() {
            return flushResult;
          }

          @Override
          public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
          }
        };
    exporter = InMemorySpanExporter.create();
    tracerProvider =
        SdkTracerProvider.builder()
            .addSpanProcessor(new StatsigSpanProcessor(SimpleSpanProcessor.create(exporter)))
            .addSpanProcessor(flushOnly)
            .build();
    OtelSingleton.instantiate(tracerProvider);
  }

  private static SpanData spanWithID(List<SpanData> spans, String spanID) {
    for (SpanData span : spans) {
      if (span.getSpanId().equals(spanID)) {
        return span;
      }
    }
    throw new AssertionError("no span with ID " + spanID + " in " + spans);
  }

  private static SpanData named(List<SpanData> spans, String name) {
    SpanData found = null;
    for (SpanData span : spans) {
      if (span.getName().equals(name)) {
        assertNull("more than one " + name + " span", found);
        found = span;
      }
    }
    assertNotNull("no " + name + " span in " + spans, found);
    return found;
  }

  private static SpanData spanWithAttribute(
      List<SpanData> spans, AttributeKey<String> key, String value) {
    for (SpanData span : spans) {
      if (value.equals(span.getAttributes().get(key))) {
        return span;
      }
    }
    throw new AssertionError("no span with " + key + "=" + value + " in " + spans);
  }

  private static List<SpanData> childrenOf(List<SpanData> spans, SpanData parent) {
    List<SpanData> children = new ArrayList<>();
    for (SpanData span : spans) {
      if (span.getParentSpanId().equals(parent.getSpanId())) {
        children.add(span);
      }
    }
    return children;
  }

  private static void pause(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
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
}
