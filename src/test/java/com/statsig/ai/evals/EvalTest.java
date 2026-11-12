package com.statsig.ai.evals;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.statsig.ai.testutil.MockScrapiServer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class EvalTest {
  private static final String API_KEY = "test-console-api-key";
  private static final String SEND_RESULTS_PATH = "/console/v1/evals/send_results";
  private static final ObjectMapper JSON = new ObjectMapper();

  private MockScrapiServer scrapi;
  private PrintStream originalErr;
  private ByteArrayOutputStream stderr;

  @Before
  public void setUp() throws IOException {
    scrapi = MockScrapiServer.create().start();
    originalErr = System.err;
    stderr = new ByteArrayOutputStream();
    System.setErr(new PrintStream(new Tee(stderr, originalErr), true));
  }

  @After
  public void tearDown() throws Exception {
    System.setErr(originalErr);
    scrapi.close();
  }

  @Test
  public void sendsResultsToStatsig() throws Exception {
    enqueueOk();

    EvalResult<String, String, String> result =
        local(
                Eval.builder("test task", greetings())
                    .task((input, hooks) -> "Hello " + input)
                    .scorer(args -> args.getOutput().equals(args.getExpected())))
            .evalRunName("run-123")
            .build()
            .run();

    assertFalse(result.isError());
    assertNull(result.getSummaryScores());
    List<EvalResultRecord<String, String, String>> results = result.getResults();
    assertEquals(2, results.size());
    assertEquals("Foo", results.get(0).getInput());
    assertEquals("Hello Foo", results.get(0).getOutput());
    assertEquals("Hi Foo", results.get(0).getExpected());
    assertFalse(results.get(0).isError());
    assertEquals(Collections.singletonMap("Grader", 0.0), results.get(0).getScores());
    assertEquals(Collections.singletonMap("Grader", 1.0), results.get(1).getScores());
    assertNotNull(results.get(0).getLatencyMs());
    assertNull(results.get(0).getInputTokens());
    assertNull(results.get(0).getOutputTokens());

    RecordedRequest request = takeRequest();
    assertEquals("POST", request.getMethod());
    assertEquals(SEND_RESULTS_PATH + "/test%20task", request.getPath());
    assertEquals("application/json", request.getHeader("Content-Type"));
    assertEquals(API_KEY, request.getHeader("STATSIG-API-KEY"));

    JsonNode body = JSON.readTree(request.getBody().readUtf8());
    assertEquals("run-123", body.get("name").asText());
    assertFalse(body.has("summaryScores"));
    assertFalse(body.has("parameters"));
    assertFalse(body.has("dataset"));
    assertEquals(
        json(
            "{'input':'Foo','output':'Hello Foo','expected':'Hi Foo','error':false,"
                + "'scores':{'Grader':{'score':0.0}}}"),
        withoutLatency(body.get("results").get(0)));
    assertEquals(
        json(
            "{'input':'Bar','output':'Hello Bar','expected':'Hello Bar','error':false,"
                + "'scores':{'Grader':{'score':1.0}}}"),
        withoutLatency(body.get("results").get(1)));
  }

  @Test
  public void encodesTheEvalNameLikeEncodeURIComponent() {
    assertEquals(
        "a%20b%2Fc%3Fd%3De%26f~!*'()%C3%A9",
        EvalResultsSender.encodeURIComponent("a b/c?d=e&f~!*'()é"));
  }

  @Test
  public void reportsNamedScorersInOrder() throws Exception {
    enqueueOk();
    Map<String, EvalScorer<String, String, String>> scorers = new LinkedHashMap<>();
    scorers.put("correctness", args -> args.getOutput().equals(args.getExpected()));
    scorers.put("startsWithHello", args -> args.getOutput().startsWith("Hello"));

    EvalResult<String, String, String> result =
        local(
                Eval.builder(
                        "test task",
                        Arrays.asList(
                            EvalDataRecord.of("Foo", "Hello Foo"),
                            EvalDataRecord.of("Baz", "Hello Bar")))
                    .task((input, hooks) -> "Hello " + input)
                    .scorers(scorers)
                    .scorer("lengthCheck", args -> args.getOutput().length() > 5))
            .build()
            .run();

    Map<String, Double> expectedFoo = new LinkedHashMap<>();
    expectedFoo.put("correctness", 1.0);
    expectedFoo.put("startsWithHello", 1.0);
    expectedFoo.put("lengthCheck", 1.0);
    assertEquals(expectedFoo, result.getResults().get(0).getScores());
    assertEquals(
        Arrays.asList("correctness", "startsWithHello", "lengthCheck"),
        new ArrayList<>(result.getResults().get(1).getScores().keySet()));
    assertEquals(0.0, result.getResults().get(1).getScores().get("correctness"), 0);

    JsonNode body = takeBody();
    assertEquals(
        json(
            "{'correctness':{'score':0.0},'startsWithHello':{'score':1.0},"
                + "'lengthCheck':{'score':1.0}}"),
        body.get("results").get(1).get("scores"));
  }

  @Test
  public void normalizesNumberBooleanAndScoreWithMetadata() throws Exception {
    enqueueOk();
    Map<String, Object> metadata = Collections.singletonMap("reason", "high confidence");

    EvalResult<String, String, String> result =
        local(
                Eval.builder("test task", greetings())
                    .task((input, hooks) -> "Hello " + input)
                    .scorer("integer", args -> 1)
                    .scorer("double", args -> 0.75)
                    .scorer("true", args -> true)
                    .scorer("false", args -> false)
                    .scorer("withMetadata", args -> ScoreWithMetadata.of(0.9, metadata))
                    .scorer("booleanWithMetadata", args -> ScoreWithMetadata.of(true, null)))
            .build()
            .run();

    Map<String, Double> expected = new LinkedHashMap<>();
    expected.put("integer", 1.0);
    expected.put("double", 0.75);
    expected.put("true", 1.0);
    expected.put("false", 0.0);
    expected.put("withMetadata", 0.9);
    expected.put("booleanWithMetadata", 1.0);
    assertEquals(expected, result.getResults().get(0).getScores());

    JsonNode scores = takeBody().get("results").get(0).get("scores");
    assertEquals(
        json("{'score':0.9,'metadata':{'reason':'high confidence'}}"), scores.get("withMetadata"));
    assertEquals(json("{'score':1.0}"), scores.get("booleanWithMetadata"));
    assertEquals(json("{'score':1.0}"), scores.get("integer"));
    assertEquals(json("{'score':0.0}"), scores.get("false"));
  }

  @Test
  public void scorerThatThrowsScoresZero() throws Exception {
    enqueueOk();

    EvalResult<String, String, String> result =
        local(
                Eval.builder("test task", Arrays.asList(EvalDataRecord.of("Test", "Hello Test")))
                    .task((input, hooks) -> "Hello " + input)
                    .scorer("goodScorer", args -> args.getOutput().equals(args.getExpected()))
                    .scorer(
                        "failingScorer",
                        args -> {
                          throw new IllegalStateException("Scorer failed");
                        })
                    .scorer(
                        "checkedScorer",
                        args -> {
                          throw new IOException("Judge unreachable");
                        })
                    .scorer("anotherGoodScorer", args -> true))
            .build()
            .run();

    EvalResultRecord<String, String, String> record = result.getResults().get(0);
    assertEquals(1.0, record.getScores().get("goodScorer"), 0);
    assertEquals(0.0, record.getScores().get("failingScorer"), 0);
    assertEquals(0.0, record.getScores().get("checkedScorer"), 0);
    assertEquals(1.0, record.getScores().get("anotherGoodScorer"), 0);
    assertFalse(record.isError());
    assertFalse(result.isError());
    assertStderrContains("[StatsigAI] Scorer 'failingScorer' failed for input Test");
    assertStderrContains("Scorer failed");
    assertStderrContains("[StatsigAI] Scorer 'checkedScorer' failed for input Test");
    assertEquals(
        json("{'score':0.0}"), takeBody().get("results").get(0).get("scores").get("failingScorer"));
  }

  @Test
  public void scorerThatReturnsAnInvalidTypeScoresZero() throws Exception {
    enqueueOk();

    EvalResult<String, String, String> result =
        local(
                Eval.builder("test task", greetings())
                    .task((input, hooks) -> "Hello " + input)
                    .scorer("string", args -> "invalid")
                    .scorer("null", args -> null)
                    .scorer("map", args -> Collections.singletonMap("score", 1)))
            .build()
            .run();

    Map<String, Double> zeros = new LinkedHashMap<>();
    zeros.put("string", 0.0);
    zeros.put("null", 0.0);
    zeros.put("map", 0.0);
    assertEquals(zeros, result.getResults().get(0).getScores());
    assertFalse(result.isError());
    assertStderrContains(
        "Scorer 'string' failed for input Foo: returned invalid type java.lang.String");
    assertStderrContains("Scorer 'null' failed for input Foo: returned invalid type null");
    assertStderrContains("Scorer 'map' failed for input Foo: returned invalid type");
    takeRequest();
  }

  @Test
  public void awaitsScorersThatReturnAFuture() throws Exception {
    enqueueOk();

    EvalResult<String, String, String> result =
        local(
                Eval.builder("test task", greetings())
                    .task((input, hooks) -> "Hello " + input)
                    .scorer("completed", args -> CompletableFuture.completedFuture(true))
                    .scorer("async", args -> CompletableFuture.supplyAsync(() -> 0.5))
                    .scorer(
                        "failed", args -> failedFuture(new IllegalStateException("Judge down"))))
            .build()
            .run();

    Map<String, Double> expected = new LinkedHashMap<>();
    expected.put("completed", 1.0);
    expected.put("async", 0.5);
    expected.put("failed", 0.0);
    assertEquals(expected, result.getResults().get(0).getScores());
    assertStderrContains(
        "Scorer 'failed' failed for input Foo: java.lang.IllegalStateException: Judge down");
    takeRequest();
  }

  @Test
  public void taskThatThrowsIsAnErrorWithZeroScores() throws Exception {
    enqueueOk();
    AtomicInteger scorerCalls = new AtomicInteger();

    EvalResult<String, String, String> result =
        local(
                Eval.builder(
                        "test task",
                        Arrays.asList(
                            EvalDataRecord.of("success", "Hello success"),
                            EvalDataRecord.of("Boom", "Anything"),
                            EvalDataRecord.of("Checked", "Anything")))
                    .task(
                        (input, hooks) -> {
                          if (input.equals("Boom")) {
                            throw new IllegalStateException("Task failed");
                          }
                          if (input.equals("Checked")) {
                            throw new IOException("Model unreachable");
                          }
                          return "Hello " + input;
                        })
                    .scorer(
                        args -> {
                          scorerCalls.incrementAndGet();
                          return 1;
                        })
                    .scorer(
                        "other",
                        args -> {
                          scorerCalls.incrementAndGet();
                          return 1;
                        }))
            .evalRunName("run-errors")
            .build()
            .run();

    assertTrue(result.isError());
    assertEquals(2, scorerCalls.get());
    EvalResultRecord<String, String, String> success = result.getResults().get(0);
    assertFalse(success.isError());
    assertEquals("Hello success", success.getOutput());
    for (EvalResultRecord<String, String, String> failed : result.getResults().subList(1, 3)) {
      assertTrue(failed.isError());
      assertNull(failed.getOutput());
      assertEquals("Anything", failed.getExpected());
      Map<String, Double> zeros = new LinkedHashMap<>();
      zeros.put("Grader", 0.0);
      zeros.put("other", 0.0);
      assertEquals(zeros, failed.getScores());
      assertNull(failed.getLatencyMs());
    }
    assertStderrContains(
        "[StatsigAI] Eval failed for input Boom: java.lang.IllegalStateException: Task failed");
    assertStderrContains("[StatsigAI] Eval failed for input Checked: java.io.IOException");

    JsonNode body = takeBody();
    assertEquals("run-errors", body.get("name").asText());
    assertFalse(body.get("results").get(0).get("error").asBoolean());
    assertEquals(
        json(
            "{'input':'Boom','output':'[Error]','expected':'Anything','error':true,"
                + "'scores':{'Grader':{'score':0.0},'other':{'score':0.0}}}"),
        body.get("results").get(1));
    assertEquals("[Error]", body.get("results").get(2).get("output").asText());
  }

  @Test
  public void awaitsAsyncTasksAndTreatsAFailedFutureAsAnError() throws Exception {
    enqueueOk();

    EvalResult<String, String, String> result =
        local(
                Eval.builder(
                        "test task",
                        Arrays.asList(
                            EvalDataRecord.of("Foo", "Hello Foo"),
                            EvalDataRecord.of("Boom", "Hello Boom"),
                            EvalDataRecord.of("Throw", "Hello Throw")))
                    .asyncTask(
                        (input, hooks) -> {
                          if (input.equals("Boom")) {
                            return failedFuture(new IllegalStateException("Task failed"));
                          }
                          if (input.equals("Throw")) {
                            throw new IllegalStateException("Task threw");
                          }
                          return CompletableFuture.supplyAsync(() -> "Hello " + input);
                        })
                    .scorer(args -> args.getOutput().equals(args.getExpected())))
            .build()
            .run();

    assertTrue(result.isError());
    EvalResultRecord<String, String, String> success = result.getResults().get(0);
    assertEquals("Hello Foo", success.getOutput());
    assertEquals(1.0, success.getScores().get("Grader"), 0);
    assertFalse(success.isError());
    for (EvalResultRecord<String, String, String> failed : result.getResults().subList(1, 3)) {
      assertTrue(failed.isError());
      assertNull(failed.getOutput());
      assertEquals(0.0, failed.getScores().get("Grader"), 0);
    }
    assertStderrContains(
        "Eval failed for input Boom: java.lang.IllegalStateException: Task failed");
    assertStderrContains(
        "Eval failed for input Throw: java.lang.IllegalStateException: Task threw");

    JsonNode results = takeBody().get("results");
    assertEquals("Hello Foo", results.get(0).get("output").asText());
    assertEquals("[Error]", results.get(1).get("output").asText());
    assertTrue(results.get(1).get("error").asBoolean());
  }

  @Test
  public void sendsHowLongTheTaskTookAsLatencyMs() throws Exception {
    enqueueOk();
    AtomicLong now = new AtomicLong(1_000_000_000L);

    EvalResult<String, String, String> result =
        local(
                Eval.builder("test task", Arrays.asList(EvalDataRecord.of("Foo", "Hello Foo")))
                    .task(
                        (input, hooks) -> {
                          now.addAndGet(250_400_000L);
                          return "Hello " + input;
                        })
                    .scorer(
                        args -> {
                          // Scoring isn't part of the task's latency.
                          now.addAndGet(1_000_000_000L);
                          return 1;
                        }))
            .nanoTime(now::get)
            .build()
            .run();

    assertEquals(Long.valueOf(250), result.getResults().get(0).getLatencyMs());
    JsonNode sent = takeBody().get("results").get(0);
    assertTrue(sent.get("latencyMs").isIntegralNumber());
    assertEquals(250, sent.get("latencyMs").asLong());
  }

  @Test
  public void timesAnAsyncTaskUntilItsFutureCompletes() throws Exception {
    enqueueOk();
    AtomicLong now = new AtomicLong();
    ScheduledExecutorService model = Executors.newSingleThreadScheduledExecutor();
    try {
      EvalResult<String, String, String> result =
          local(
                  Eval.builder("test task", Arrays.asList(EvalDataRecord.of("Foo", "Hello Foo")))
                      .asyncTask(
                          (input, hooks) -> {
                            CompletableFuture<String> response = new CompletableFuture<>();
                            // The model answers on another thread, well after the task returns.
                            model.schedule(
                                () -> {
                                  now.addAndGet(1_200_000_000L);
                                  hooks.reportTokens(12, 3);
                                  response.complete("Hello " + input);
                                },
                                50,
                                TimeUnit.MILLISECONDS);
                            return response;
                          })
                      .scorer(args -> args.getOutput().equals(args.getExpected())))
              .nanoTime(now::get)
              .build()
              .run();

      EvalResultRecord<String, String, String> record = result.getResults().get(0);
      assertEquals(1.0, record.getScores().get("Grader"), 0);
      assertEquals(Long.valueOf(1200), record.getLatencyMs());
      assertEquals(Long.valueOf(12), record.getInputTokens());
      assertEquals(Long.valueOf(3), record.getOutputTokens());
      JsonNode sent = takeBody().get("results").get(0);
      assertEquals(1200, sent.get("latencyMs").asLong());
      assertEquals(12, sent.get("inputTokens").asLong());
      assertEquals(3, sent.get("outputTokens").asLong());
    } finally {
      model.shutdownNow();
    }
  }

  @Test
  public void sendsTheSumOfTheTokensATaskReports() throws Exception {
    enqueueOk();
    AtomicReference<EvalHooks> taskHooks = new AtomicReference<>();

    EvalResult<String, String, String> result =
        local(
                Eval.builder("test task", Arrays.asList(EvalDataRecord.of("Foo", "Hello Foo")))
                    .task(
                        (input, hooks) -> {
                          taskHooks.set(hooks);
                          hooks.reportTokens(120, 30);
                          hooks.reportTokens(80, 12);
                          return "Hello " + input;
                        })
                    .scorer(
                        args -> {
                          // The output is ready, so this doesn't count.
                          taskHooks.get().reportTokens(1000, 1000);
                          return 1;
                        }))
            .build()
            .run();

    EvalResultRecord<String, String, String> record = result.getResults().get(0);
    assertEquals(Long.valueOf(200), record.getInputTokens());
    assertEquals(Long.valueOf(42), record.getOutputTokens());
    assertEquals(
        json(
            "{'input':'Foo','output':'Hello Foo','expected':'Hello Foo','error':false,"
                + "'inputTokens':200,'outputTokens':42,'scores':{'Grader':{'score':1.0}}}"),
        withoutLatency(takeBody().get("results").get(0)));
  }

  @Test
  public void addsUpTokensReportedFromManyThreads() throws Exception {
    enqueueOk();
    int threads = 8;
    int callsPerThread = 1000;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      EvalResult<String, String, String> result =
          local(
                  Eval.builder("test task", Arrays.asList(EvalDataRecord.of("Foo", "Hello Foo")))
                      .asyncTask(
                          (input, hooks) -> {
                            List<CompletableFuture<Void>> calls = new ArrayList<>();
                            for (int t = 0; t < threads; t++) {
                              calls.add(
                                  CompletableFuture.runAsync(
                                      () -> {
                                        for (int i = 0; i < callsPerThread; i++) {
                                          hooks.reportTokens(2, 1);
                                        }
                                      },
                                      pool));
                            }
                            return CompletableFuture.allOf(
                                    calls.toArray(new CompletableFuture<?>[0]))
                                .thenApply(ignored -> "Hello " + input);
                          })
                      .scorer(args -> 1))
              .build()
              .run();

      EvalResultRecord<String, String, String> record = result.getResults().get(0);
      assertEquals(Long.valueOf(2L * threads * callsPerThread), record.getInputTokens());
      assertEquals(Long.valueOf((long) threads * callsPerThread), record.getOutputTokens());
      takeRequest();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  public void sendsNoTokensForARecordWhoseTaskNeverReportsThem() throws Exception {
    enqueueOk();

    EvalResult<String, String, String> result =
        local(
                Eval.builder("test task", greetings())
                    .task(
                        (input, hooks) -> {
                          if (input.equals("Foo")) {
                            hooks.reportTokens(10, 2);
                          }
                          return "Hello " + input;
                        })
                    .scorer(args -> 1))
            .build()
            .run();

    EvalResultRecord<String, String, String> foo = result.getResults().get(0);
    EvalResultRecord<String, String, String> bar = result.getResults().get(1);
    assertEquals(Long.valueOf(10), foo.getInputTokens());
    assertEquals(Long.valueOf(2), foo.getOutputTokens());
    assertNotNull(bar.getLatencyMs());
    assertNull(bar.getInputTokens());
    assertNull(bar.getOutputTokens());

    JsonNode results = takeBody().get("results");
    assertEquals(10, results.get(0).get("inputTokens").asLong());
    assertEquals(2, results.get(0).get("outputTokens").asLong());
    assertTrue(results.get(1).get("latencyMs").isIntegralNumber());
    assertFalse(results.get(1).has("inputTokens"));
    assertFalse(results.get(1).has("outputTokens"));
  }

  @Test
  public void sendsNoLatencyOrTokensForARecordWhoseTaskFails() throws Exception {
    enqueueOk();

    EvalResult<String, String, String> result =
        local(
                Eval.builder(
                        "test task",
                        Arrays.asList(
                            EvalDataRecord.of("Throw", "Anything"),
                            EvalDataRecord.of("Fail", "Anything")))
                    .<String>asyncTask(
                        (input, hooks) -> {
                          hooks.reportTokens(10, 2);
                          if (input.equals("Throw")) {
                            throw new IllegalStateException("Task threw");
                          }
                          return failedFuture(new IllegalStateException("Task failed"));
                        })
                    .scorer(args -> 1))
            .build()
            .run();

    JsonNode results = takeBody().get("results");
    for (int i = 0; i < 2; i++) {
      EvalResultRecord<String, String, String> record = result.getResults().get(i);
      assertTrue(record.isError());
      assertNull(record.getLatencyMs());
      assertNull(record.getInputTokens());
      assertNull(record.getOutputTokens());
      JsonNode sent = results.get(i);
      assertTrue(sent.get("error").asBoolean());
      assertFalse(sent.has("latencyMs"));
      assertFalse(sent.has("inputTokens"));
      assertFalse(sent.has("outputTokens"));
    }
  }

  @Test
  public void ignoresNegativeTokenCounts() throws Exception {
    enqueueOk();

    EvalResult<String, String, String> result =
        local(
                Eval.builder("test task", Arrays.asList(EvalDataRecord.of("Foo", "Hello Foo")))
                    .task(
                        (input, hooks) -> {
                          hooks.reportTokens(-1, 5);
                          hooks.reportTokens(5, -1);
                          hooks.reportTokens(7, 3);
                          return "Hello " + input;
                        })
                    .scorer(args -> 1))
            .build()
            .run();

    EvalResultRecord<String, String, String> record = result.getResults().get(0);
    assertFalse(record.isError());
    assertEquals(Long.valueOf(7), record.getInputTokens());
    assertEquals(Long.valueOf(3), record.getOutputTokens());
    assertStderrContains(
        "[StatsigAI] reportTokens() needs inputTokens and outputTokens of at least 0. Ignoring: -1,"
            + " 5");
    assertStderrContains("Ignoring: 5, -1");
    JsonNode sent = takeBody().get("results").get(0);
    assertEquals(7, sent.get("inputTokens").asLong());
    assertEquals(3, sent.get("outputTokens").asLong());
  }

  @Test
  public void sendsObjectInputsOutputsAndMetadataAsJsonStrings() throws Exception {
    enqueueOk();
    Map<String, Object> question = Collections.singletonMap("question", "Hi?");
    Answer expectedAnswer = new Answer("Hello");
    AtomicReference<Answer> taskOutput = new AtomicReference<>();
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("reasoning", "Greets by name");
    metadata.put("confidence", 0.9);
    metadata.put("tags", Collections.singletonList("greeting"));
    metadata.put("count", 3);
    metadata.put("missing", null);

    EvalResult<Map<String, Object>, Answer, Answer> result =
        local(
                Eval.builder(
                        "test task", Arrays.asList(EvalDataRecord.of(question, expectedAnswer)))
                    .task(
                        (input, hooks) -> {
                          Answer answer = new Answer("Hello");
                          taskOutput.set(answer);
                          return answer;
                        })
                    .scorer(
                        args ->
                            ScoreWithMetadata.of(
                                args.getOutput().getAnswer().equals(args.getExpected().getAnswer()),
                                metadata)))
            .build()
            .run();

    EvalResultRecord<Map<String, Object>, Answer, Answer> record = result.getResults().get(0);
    assertSame(question, record.getInput());
    assertSame(taskOutput.get(), record.getOutput());
    assertSame(expectedAnswer, record.getExpected());
    assertEquals(1.0, record.getScores().get("Grader"), 0);

    JsonNode sent = takeBody().get("results").get(0);
    assertEquals("{\"question\":\"Hi?\"}", sent.get("input").asText());
    assertEquals("{\"answer\":\"Hello\"}", sent.get("output").asText());
    assertEquals("{\"answer\":\"Hello\"}", sent.get("expected").asText());
    JsonNode sentMetadata = sent.get("scores").get("Grader").get("metadata");
    assertEquals("Greets by name", sentMetadata.get("reasoning").asText());
    assertEquals("0.9", sentMetadata.get("confidence").asText());
    assertEquals("[\"greeting\"]", sentMetadata.get("tags").asText());
    assertEquals("3", sentMetadata.get("count").asText());
    assertFalse(sentMetadata.has("missing"));
    assertTrue(sentMetadata.get("confidence").isTextual());
  }

  @Test
  public void sendsAnEmptyOutputWhenTheTaskReturnsNull() throws Exception {
    enqueueOk();

    EvalResult<String, String, Void> result =
        local(
                Eval.builder("test task", Arrays.asList(EvalDataRecord.<String, Void>of("Foo")))
                    .task((input, hooks) -> (String) null)
                    .scorer(args -> 1))
            .build()
            .run();

    assertFalse(result.isError());
    assertNull(result.getResults().get(0).getOutput());
    JsonNode sent = takeBody().get("results").get(0);
    assertEquals("Foo", sent.get("input").asText());
    assertEquals("", sent.get("output").asText());
    assertFalse(sent.has("expected"));
    assertFalse(sent.has("category"));
  }

  @Test
  public void passesParameterDefaultsToHooksAndSendsThem() throws Exception {
    enqueueOk();
    Map<String, Object> nested = Collections.singletonMap("nested", "value");
    Map<String, Object> more = new LinkedHashMap<>();
    more.put("count", 123);
    more.put("config", nested);
    AtomicReference<Map<String, Object>> seenParameters = new AtomicReference<>();

    EvalResult<String, String, String> result =
        local(
                Eval.builder("test task", Arrays.asList(EvalDataRecord.of("world", "Hi world!")))
                    .task(
                        (input, hooks) -> {
                          seenParameters.set(hooks.getParameters());
                          String prefix = hooks.getParameter("prefix");
                          return prefix + " " + input + hooks.getParameter("suffix");
                        })
                    .scorer(args -> args.getOutput().equals(args.getExpected())))
            .parameter("prefix", "Hi")
            .parameter("suffix", "!")
            .parameters(more)
            .build()
            .run();

    assertEquals("Hi world!", result.getResults().get(0).getOutput());
    assertEquals(1.0, result.getResults().get(0).getScores().get("Grader"), 0);
    Map<String, Object> expectedParameters = new LinkedHashMap<>();
    expectedParameters.put("prefix", "Hi");
    expectedParameters.put("suffix", "!");
    expectedParameters.put("count", 123);
    expectedParameters.put("config", nested);
    assertEquals(expectedParameters, seenParameters.get());

    Map<String, String> sentParameters = new LinkedHashMap<>();
    sentParameters.put("prefix", "Hi");
    sentParameters.put("suffix", "!");
    sentParameters.put("count", "123");
    sentParameters.put("config", "{\"nested\":\"value\"}");
    assertEquals(JSON.valueToTree(sentParameters), takeBody().get("parameters"));
  }

  @Test
  public void passesTheCategoryToHooksAndScorers() throws Exception {
    enqueueOk();
    Map<String, List<String>> taskCategories = new ConcurrentHashMap<>();
    Map<String, List<String>> scorerCategories = new ConcurrentHashMap<>();

    EvalResult<String, String, String> result =
        local(
                Eval.builder(
                        "test task",
                        Arrays.asList(
                            EvalDataRecord.of("Foo", "greeting: Hello Foo", "greeting"),
                            EvalDataRecord.of("Bar", "Hello Bar", Arrays.asList("math", "hard")),
                            EvalDataRecord.of("Baz", "Hello Baz")))
                    .task(
                        (input, hooks) -> {
                          taskCategories.put(input, hooks.getCategories());
                          return hooks.getCategories().contains("greeting")
                              ? "greeting: Hello " + input
                              : "Hello " + input;
                        })
                    .scorer(
                        args -> {
                          scorerCategories.put(args.getInput(), args.getCategories());
                          return args.getOutput().equals(args.getExpected());
                        }))
            .build()
            .run();

    assertEquals(Collections.singletonList("greeting"), taskCategories.get("Foo"));
    assertEquals(Arrays.asList("math", "hard"), taskCategories.get("Bar"));
    assertEquals(Collections.emptyList(), taskCategories.get("Baz"));
    assertEquals(taskCategories, scorerCategories);
    assertEquals(1.0, result.getResults().get(0).getScores().get("Grader"), 0);
    assertEquals(Arrays.asList("math", "hard"), result.getResults().get(1).getCategories());

    JsonNode results = takeBody().get("results");
    assertEquals(json("'greeting'"), results.get(0).get("category"));
    assertEquals(json("['math','hard']"), results.get(1).get("category"));
    assertFalse(results.get(2).has("category"));
  }

  @Test
  public void computesAndSendsSummaryScores() throws Exception {
    enqueueOk();

    EvalResult<String, String, String> result =
        local(
                Eval.builder(
                        "test task",
                        Arrays.asList(
                            EvalDataRecord.of("Foo", "Hello Foo"),
                            EvalDataRecord.of("Bar", "Hello Bar")))
                    .task((input, hooks) -> "Hello " + input)
                    .scorer("correctness", args -> args.getOutput().equals(args.getExpected())))
            .summaryScores(
                results -> {
                  Map<String, Number> summary = new LinkedHashMap<>();
                  summary.put(
                      "correctness",
                      results.stream()
                          .filter(r -> r.getScores().get("correctness") == 1.0)
                          .count());
                  summary.put(
                      "length", results.stream().mapToInt(r -> r.getOutput().length()).sum());
                  return summary;
                })
            .build()
            .run();

    Map<String, Double> expected = new LinkedHashMap<>();
    expected.put("correctness", 2.0);
    expected.put("length", 18.0);
    assertEquals(expected, result.getSummaryScores());
    assertEquals(json("{'correctness':2.0,'length':18.0}"), takeBody().get("summaryScores"));
  }

  @Test
  public void summaryScorerThatThrowsGivesNoSummaryScores() throws Exception {
    enqueueOk();

    EvalResult<String, String, String> result =
        local(
                Eval.builder("test task", greetings())
                    .task((input, hooks) -> "Hello " + input)
                    .scorer(args -> 1))
            .summaryScores(
                results -> {
                  throw new IllegalStateException("Summary scorer failed");
                })
            .build()
            .run();

    assertNull(result.getSummaryScores());
    assertEquals(2, result.getResults().size());
    assertStderrContains("[StatsigAI] Summary scorer failed: java.lang.IllegalStateException");
    JsonNode body = takeBody();
    assertFalse(body.has("summaryScores"));
    assertEquals(2, body.get("results").size());
  }

  @Test
  public void doesNotSendNonFiniteSummaryScores() throws Exception {
    EvalResult<String, String, String> result =
        local(
                Eval.builder("test task", greetings())
                    .task((input, hooks) -> "Hello " + input)
                    .scorer(args -> 1))
            .summaryScores(results -> Collections.singletonMap("average", Double.NaN))
            .build()
            .run();

    assertTrue(result.getSummaryScores().get("average").isNaN());
    assertNull(scrapi.takeRequest(200, TimeUnit.MILLISECONDS));
    assertStderrContains(
        "[StatsigAI] Error sending eval results: java.lang.IllegalArgumentException: summary values"
            + " must be finite numbers");
  }

  @Test
  public void failsFastWithoutAnApiKey() {
    for (String envValue : Arrays.asList(null, "")) {
      Eval.Builder<String, String, String> builder =
          Eval.builder("test task", greetings())
              .task((input, hooks) -> "Hello " + input)
              .scorer(args -> 1)
              .endpoint(scrapi.url(SEND_RESULTS_PATH))
              .getenv(name -> envValue);

      IllegalStateException error = assertThrows(IllegalStateException.class, builder::build);
      assertTrue(error.getMessage().contains("Missing Statsig Console API key"));
      assertTrue(error.getMessage().contains("STATSIG_API_KEY"));
    }
    assertEquals(0, scrapi.getServer().getRequestCount());
  }

  @Test
  public void readsTheApiKeyFromTheEnvironmentUnlessOneIsGiven() throws Exception {
    enqueueOk();
    enqueueOk();
    Eval.Builder<String, String, String> builder =
        Eval.builder("test task", greetings())
            .task((input, hooks) -> "Hello " + input)
            .scorer(args -> 1)
            .endpoint(scrapi.url(SEND_RESULTS_PATH))
            .getenv(name -> name.equals("STATSIG_API_KEY") ? "env-key" : null);

    builder.build().run();
    assertEquals("env-key", takeRequest().getHeader("STATSIG-API-KEY"));

    builder.apiKey("explicit-key").build().run();
    assertEquals("explicit-key", takeRequest().getHeader("STATSIG-API-KEY"));
  }

  @Test
  public void failedSendIsLoggedNotThrown() throws Exception {
    scrapi.enqueue(
        new MockResponse().setResponseCode(404).setBody("{\"message\":\"Eval not found\"}"));
    Eval.Builder<String, String, String> builder =
        local(
            Eval.builder("test task", greetings())
                .task((input, hooks) -> "Hello " + input)
                .scorer(args -> 1));

    EvalResult<String, String, String> notFound = builder.build().run();
    assertEquals(2, notFound.getResults().size());
    assertStderrContains("[StatsigAI] Failed to send eval results: 404");
    assertStderrContains("Eval not found");
    takeRequest();

    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    EvalResult<String, String, String> unreachable =
        builder.endpoint("http://127.0.0.1:" + closedPort + SEND_RESULTS_PATH).build().run();
    assertEquals(2, unreachable.getResults().size());
    assertStderrContains("[StatsigAI] Error sending eval results: java.net.ConnectException");
  }

  @Test
  public void runAsyncReturnsBeforeTheTasksFinish() throws Exception {
    enqueueOk();
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger executions = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      CompletableFuture<EvalResult<String, String, String>> future =
          local(
                  Eval.builder("test task", greetings())
                      .task(
                          (input, hooks) -> {
                            release.await(5, TimeUnit.SECONDS);
                            return "Hello " + input;
                          })
                      .scorer(args -> args.getOutput().equals(args.getExpected())))
              .executor(
                  runnable -> {
                    executions.incrementAndGet();
                    pool.execute(runnable);
                  })
              .build()
              .runAsync();

      assertFalse(future.isDone());
      release.countDown();
      EvalResult<String, String, String> result = future.get(5, TimeUnit.SECONDS);

      assertEquals(1.0, result.getResults().get(1).getScores().get("Grader"), 0);
      // Two tasks, two scorer runs, and sending the results.
      assertEquals(5, executions.get());
      assertNotNull(takeRequest());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  public void runsRecordsConcurrentlyAndKeepsTheirOrder() throws Exception {
    enqueueOk();
    List<EvalDataRecord<String, String>> data = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      data.add(EvalDataRecord.of("r" + i, "Hello r" + i));
    }
    CountDownLatch allStarted = new CountDownLatch(data.size());

    EvalResult<String, String, String> result =
        local(
                Eval.builder("test task", data)
                    .task(
                        (input, hooks) -> {
                          allStarted.countDown();
                          if (!allStarted.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("records ran one at a time");
                          }
                          // Earlier records finish last.
                          Thread.sleep(10L * (data.size() - Integer.parseInt(input.substring(1))));
                          return "Hello " + input;
                        })
                    .scorer(args -> args.getOutput().equals(args.getExpected())))
            .build()
            .run();

    assertFalse(result.isError());
    for (int i = 0; i < data.size(); i++) {
      assertEquals("r" + i, result.getResults().get(i).getInput());
      assertEquals(1.0, result.getResults().get(i).getScores().get("Grader"), 0);
    }
    JsonNode results = takeBody().get("results");
    assertEquals("r0", results.get(0).get("input").asText());
    assertEquals("r3", results.get(3).get("input").asText());
  }

  @Test
  public void runsScorersForARecordConcurrently() throws Exception {
    enqueueOk();
    CountDownLatch bothStarted = new CountDownLatch(2);
    EvalScorer<String, String, String> waitForTheOther =
        args -> {
          bothStarted.countDown();
          return bothStarted.await(5, TimeUnit.SECONDS);
        };

    EvalResult<String, String, String> result =
        local(
                Eval.builder("test task", Arrays.asList(EvalDataRecord.of("Foo", "Hello Foo")))
                    .task((input, hooks) -> "Hello " + input)
                    .scorer("first", waitForTheOther)
                    .scorer("second", waitForTheOther))
            .build()
            .run();

    assertEquals(1.0, result.getResults().get(0).getScores().get("first"), 0);
    assertEquals(1.0, result.getResults().get(0).getScores().get("second"), 0);
    takeRequest();
  }

  @Test
  public void tasksSeeTheCallersOpenTelemetryContext() throws Exception {
    enqueueOk();
    ContextKey<String> key = ContextKey.named("eval-test");
    EvalResult<String, String, String> result;
    try (Scope ignored = Context.current().with(key, "outer").makeCurrent()) {
      result =
          local(
                  Eval.builder("test task", greetings())
                      .task((input, hooks) -> Context.current().get(key))
                      .scorer(args -> "outer".equals(Context.current().get(key))))
              .build()
              .run();
    }

    assertEquals("outer", result.getResults().get(0).getOutput());
    assertEquals(1.0, result.getResults().get(0).getScores().get("Grader"), 0);
    takeRequest();
  }

  @Test
  public void builderNeedsAScorerWithAUniqueName() {
    Eval.Builder<String, String, String> noScorer =
        Eval.builder("test task", greetings())
            .task((input, hooks) -> "Hello " + input)
            .apiKey(API_KEY);
    assertThrows(IllegalStateException.class, noScorer::build);

    Eval.Builder<String, String, String> oneScorer = noScorer.scorer(args -> 1);
    assertThrows(IllegalArgumentException.class, () -> oneScorer.scorer(args -> 0));
    assertThrows(IllegalArgumentException.class, () -> oneScorer.scorer("Grader", args -> 0));
  }

  // Each lambda-taking method must accept an inline lambda. Overloads such as Function/Consumer
  // pairs make `x -> value` ambiguous, so this fails to compile if one comes back.
  @Test
  public void acceptsInlineLambdas() throws Exception {
    enqueueOk();
    List<EvalDataRecord<String, String>> data = greetings();
    Map<String, EvalScorer<String, String, String>> named = new LinkedHashMap<>();
    named.put("exact", args -> args.getOutput().equals(args.getExpected()));
    named.put("nonEmpty", args -> !args.getOutput().isEmpty());

    Eval<String, String, String> sync =
        local(
                Eval.builder("test task", data)
                    .task((input, hooks) -> "Hello " + input)
                    .scorer(args -> args.getOutput().length())
                    .scorer("double", args -> 0.5)
                    .scorer("boolean", args -> true)
                    .scorer("metadata", args -> ScoreWithMetadata.of(1, null))
                    .scorer("future", args -> CompletableFuture.completedFuture(1))
                    .scorers(named))
            .summaryScores(results -> Collections.singletonMap("count", results.size()))
            .executor(runnable -> new Thread(runnable).start())
            .build();

    Eval<String, Integer, String> async =
        Eval.builder("test task", data)
            .asyncTask((input, hooks) -> CompletableFuture.completedFuture(input.length()))
            .scorer(args -> args.getOutput() > 2)
            .summaryScores(results -> Collections.singletonMap("count", 1.0))
            .apiKey(API_KEY)
            .build();

    Eval<String, String, String> fromIterable =
        Eval.builder("test task", () -> data.iterator())
            .task(EvalTest::greet)
            .scorer(EvalTest::exactMatch)
            .apiKey(API_KEY)
            .build();

    assertNotNull(async);
    assertNotNull(fromIterable);
    EvalResult<String, String, String> result = sync.run();
    assertEquals(9.0, result.getResults().get(0).getScores().get("Grader"), 0);
    assertEquals(Collections.singletonMap("count", 2.0), result.getSummaryScores());
    takeRequest();
  }

  private static String greet(String input, EvalHooks hooks) {
    return "Hello " + input;
  }

  private static boolean exactMatch(EvalScorerArgs<String, String, String> args) {
    return args.getOutput().equals(args.getExpected());
  }

  public static final class Answer {
    private final String answer;

    Answer(String answer) {
      this.answer = answer;
    }

    public String getAnswer() {
      return answer;
    }
  }

  private static List<EvalDataRecord<String, String>> greetings() {
    return Arrays.asList(EvalDataRecord.of("Foo", "Hi Foo"), EvalDataRecord.of("Bar", "Hello Bar"));
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

  // Checks that a sent result has a latencyMs in whole ms, and returns the rest of it, since the
  // latency varies from run to run.
  private static JsonNode withoutLatency(JsonNode result) {
    JsonNode latency = result.get("latencyMs");
    assertTrue(
        "latencyMs should be a whole number of ms: " + result,
        latency != null && latency.isIntegralNumber() && latency.asLong() >= 0);
    ObjectNode rest = result.deepCopy();
    rest.remove("latencyMs");
    return rest;
  }

  // JSON with single quotes, to keep the expected payloads readable.
  private static JsonNode json(String singleQuoted) throws IOException {
    return JSON.readTree(singleQuoted.replace('\'', '"'));
  }

  private void assertStderrContains(String expected) {
    String output = stderr.toString();
    assertTrue(
        "stderr should contain: " + expected + "\nstderr was:\n" + output,
        output.contains(expected));
  }

  private static <T> CompletableFuture<T> failedFuture(Throwable error) {
    CompletableFuture<T> future = new CompletableFuture<>();
    future.completeExceptionally(error);
    return future;
  }

  private static final class Tee extends OutputStream {
    private final OutputStream first;
    private final OutputStream second;

    Tee(OutputStream first, OutputStream second) {
      this.first = first;
      this.second = second;
    }

    @Override
    public void write(int b) throws IOException {
      first.write(b);
      second.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      first.write(b, off, len);
      second.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
      first.flush();
      second.flush();
    }
  }
}
