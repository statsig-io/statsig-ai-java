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
import com.statsig.ai.testutil.MockScrapiServer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class EvalTest {
  private static final String API_KEY = "test-console-api-key";
  private static final String SEND_RESULTS_PATH = "/console/v1/evals/send_results";
  private static final String DATASETS_PATH = "/console/v1/eval_datasets";
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
        body.get("results").get(0));
    assertEquals(
        json(
            "{'input':'Bar','output':'Hello Bar','expected':'Hello Bar','error':false,"
                + "'scores':{'Grader':{'score':1.0}}}"),
        body.get("results").get(1));
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

    Eval<String, String, String> onDataset =
        Eval.builder(
                "test task",
                StatsigDataset.of(
                    "golden",
                    row ->
                        EvalDataRecord.of(
                            row.getColumns().get("question"),
                            row.getColumns().get("answer"),
                            row.getGroups())))
            .task((input, hooks) -> "Hello " + input)
            .scorer(args -> args.getOutput().equals(args.getExpected()))
            .dataset("golden v2")
            .apiKey(API_KEY)
            .build();

    assertNotNull(async);
    assertNotNull(fromIterable);
    assertNotNull(onDataset);
    EvalResult<String, String, String> result = sync.run();
    assertEquals(9.0, result.getResults().get(0).getScores().get("Grader"), 0);
    assertEquals(Collections.singletonMap("count", 2.0), result.getSummaryScores());
    takeRequest();
  }

  @Test
  public void loadsEveryPageOfADatasetAndSendsItsRowIDs() throws Exception {
    enqueueDatasetPage(0, 100);
    enqueueDatasetPage(100, 200);
    enqueueDatasetPage(200, 201);
    enqueueOk();

    EvalResult<String, String, String> result =
        local(
                Eval.builder("dataset eval", goldenSet())
                    .task((input, hooks) -> input.replace('q', 'a'))
                    .scorer(args -> args.getOutput().equals(args.getExpected())))
            .build()
            .run();

    assertEquals(201, result.getResults().size());
    for (int page = 1; page <= 3; page++) {
      RecordedRequest request = takeRequest();
      assertEquals("GET", request.getMethod());
      assertEquals(
          DATASETS_PATH + "/golden%20set/rows?page=" + page + "&limit=100", request.getPath());
      assertEquals(API_KEY, request.getHeader("STATSIG-API-KEY"));
    }
    for (int i = 0; i < 201; i++) {
      EvalResultRecord<String, String, String> record = result.getResults().get(i);
      assertEquals("row-" + i, record.getRowID());
      assertEquals("q" + i, record.getInput());
      assertEquals(1.0, record.getScores().get("Grader"), 0);
    }

    RecordedRequest send = takeRequest();
    assertEquals(SEND_RESULTS_PATH + "/dataset%20eval", send.getPath());
    JsonNode body = JSON.readTree(send.getBody().readUtf8());
    assertEquals("golden set", body.get("dataset").asText());
    assertEquals(
        json(
            "{'input':'q200','output':'a200','expected':'a200','category':['math'],"
                + "'rowID':'row-200','error':false,'scores':{'Grader':{'score':1.0}}}"),
        body.get("results").get(200));
    assertEquals("row-0", body.get("results").get(0).get("rowID").asText());
  }

  @Test
  public void mapsDatasetRowsToRecordsThatKeepTheRowsID() throws Exception {
    scrapi.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(
                json("{'data':["
                        + "{'rowID':'row-a','columns':[{'name':'question','value':'qa'},"
                        + "{'name':'answer','value':'aa'},{'name':'notes','value':'hard'}],"
                        + "'groups':['math','easy']},"
                        + "{'rowID':'row-b','columns':[{'name':'question','value':'qb'}]},"
                        + "{'rowID':'row-c','columns':[{'name':'question','value':'qc'},"
                        + "{'name':'answer','value':'ac'}],'groups':[]}]}")
                    .toString()));
    enqueueOk();
    List<StatsigDatasetRow> rows = Collections.synchronizedList(new ArrayList<>());
    StatsigDataset<String, String> dataset =
        StatsigDataset.of(
            "golden",
            row -> {
              rows.add(row);
              EvalDataRecord<String, String> record =
                  EvalDataRecord.of(
                      row.getColumns().get("question"),
                      row.getColumns().get("answer"),
                      row.getGroups());
              // The row's own ID wins over one toRecord sets.
              return row.getRowID().equals("row-b") ? record.withRowID("caller-set") : record;
            });

    EvalResult<String, String, String> result =
        local(
                Eval.builder("dataset eval", dataset)
                    .task((input, hooks) -> input)
                    .scorer("rowID", args -> args.getRowID().startsWith("row-")))
            .build()
            .run();

    assertEquals(3, rows.size());
    Map<String, String> columns = new LinkedHashMap<>();
    columns.put("question", "qa");
    columns.put("answer", "aa");
    columns.put("notes", "hard");
    assertEquals("row-a", rows.get(0).getRowID());
    assertEquals(columns, rows.get(0).getColumns());
    assertEquals(
        Arrays.asList("question", "answer", "notes"),
        new ArrayList<>(rows.get(0).getColumns().keySet()));
    assertEquals(Arrays.asList("math", "easy"), rows.get(0).getGroups());
    assertEquals(Collections.singletonMap("question", "qb"), rows.get(1).getColumns());
    assertEquals(Collections.emptyList(), rows.get(1).getGroups());
    assertEquals(Collections.emptyList(), rows.get(2).getGroups());

    List<EvalResultRecord<String, String, String>> results = result.getResults();
    assertEquals("row-a", results.get(0).getRowID());
    assertEquals("row-b", results.get(1).getRowID());
    assertEquals("row-c", results.get(2).getRowID());
    assertEquals(Arrays.asList("math", "easy"), results.get(0).getCategories());
    assertNull(results.get(1).getExpected());
    for (EvalResultRecord<String, String, String> record : results) {
      assertEquals(1.0, record.getScores().get("rowID"), 0);
    }

    takeRequest();
    JsonNode sent = takeBody().get("results");
    assertEquals("row-b", sent.get(1).get("rowID").asText());
    assertEquals(json("['math','easy']"), sent.get(0).get("category"));
  }

  @Test
  public void sendsTheDatasetOptionWithCallerBuiltRecords() throws Exception {
    enqueueOk();

    EvalResult<String, String, String> result =
        local(
                Eval.builder(
                        "test task",
                        Arrays.asList(
                            EvalDataRecord.of("Foo", "Hello Foo").withRowID("row-1"),
                            EvalDataRecord.of("Bar", "Hello Bar", "greeting").withRowID("row-2")))
                    .task((input, hooks) -> "Hello " + input)
                    .scorer(args -> args.getOutput().equals(args.getExpected())))
            .dataset("golden")
            .build()
            .run();

    assertEquals("row-1", result.getResults().get(0).getRowID());
    assertEquals("row-2", result.getResults().get(1).getRowID());
    RecordedRequest request = takeRequest();
    assertEquals(SEND_RESULTS_PATH + "/test%20task", request.getPath());
    JsonNode body = JSON.readTree(request.getBody().readUtf8());
    assertEquals("golden", body.get("dataset").asText());
    assertEquals(
        json(
            "{'input':'Foo','output':'Hello Foo','expected':'Hello Foo','rowID':'row-1',"
                + "'error':false,'scores':{'Grader':{'score':1.0}}}"),
        body.get("results").get(0));
    assertEquals(
        json(
            "{'input':'Bar','output':'Hello Bar','expected':'Hello Bar','category':'greeting',"
                + "'rowID':'row-2','error':false,'scores':{'Grader':{'score':1.0}}}"),
        body.get("results").get(1));
    assertEquals(1, scrapi.getServer().getRequestCount());
  }

  @Test
  public void datasetOptionReplacesTheStatsigDatasetsName() throws Exception {
    enqueueDatasetPage(0, 2);
    enqueueOk();

    local(Eval.builder("dataset eval", goldenSet()).task((input, hooks) -> input).scorer(args -> 1))
        .dataset("golden v2")
        .build()
        .run();

    assertEquals(DATASETS_PATH + "/golden%20set/rows?page=1&limit=100", takeRequest().getPath());
    assertEquals("golden v2", takeBody().get("dataset").asText());
  }

  @Test
  public void sendsRowIDsWithoutCheckingThemWhenNoDatasetIsNamed() throws Exception {
    enqueueOk();

    EvalResult<String, String, String> result =
        local(
                Eval.builder(
                        "test task",
                        Arrays.asList(
                            EvalDataRecord.of("Foo", "Hello Foo").withRowID("row-0"),
                            EvalDataRecord.of("Bar", "Hello Bar").withRowID("row-0"),
                            EvalDataRecord.<String, String>of("Baz")))
                    .task((input, hooks) -> "Hello " + input)
                    .scorer(args -> 1))
            .build()
            .run();

    assertEquals(3, result.getResults().size());
    assertNull(result.getResults().get(2).getRowID());
    JsonNode body = takeBody();
    assertFalse(body.has("dataset"));
    assertEquals("row-0", body.get("results").get(1).get("rowID").asText());
    assertFalse(body.get("results").get(2).has("rowID"));
  }

  @Test
  public void checksRowIDsBeforeAnyTaskRunsWhenTheEvalNamesADataset() throws Exception {
    Map<String, List<EvalDataRecord<String, String>>> cases = new LinkedHashMap<>();
    cases.put(
        "[StatsigAI] data[1] needs a rowID when the eval names a dataset.",
        Arrays.asList(
            EvalDataRecord.<String, String>of("q0").withRowID("row-0"),
            EvalDataRecord.<String, String>of("q1")));
    cases.put(
        "[StatsigAI] data[0] needs a rowID when the eval names a dataset.",
        Arrays.asList(EvalDataRecord.<String, String>of("q0").withRowID("")));
    cases.put(
        "[StatsigAI] data[1] repeats rowID row-0; each record needs its own dataset row.",
        Arrays.asList(
            EvalDataRecord.<String, String>of("q0").withRowID("row-0"),
            EvalDataRecord.<String, String>of("q1").withRowID("row-0")));
    AtomicInteger taskCalls = new AtomicInteger();

    for (Map.Entry<String, List<EvalDataRecord<String, String>>> entry : cases.entrySet()) {
      Eval<String, String, String> eval =
          local(
                  Eval.builder("dataset eval", entry.getValue())
                      .task(
                          (input, hooks) -> {
                            taskCalls.incrementAndGet();
                            return input;
                          })
                      .scorer(args -> 1))
              .dataset("golden")
              .build();

      IllegalArgumentException error = assertThrows(IllegalArgumentException.class, eval::run);
      assertEquals(entry.getKey(), error.getMessage());
      ExecutionException asyncError =
          assertThrows(ExecutionException.class, () -> eval.runAsync().get(5, TimeUnit.SECONDS));
      assertEquals(entry.getKey(), asyncError.getCause().getMessage());
    }

    assertEquals(0, taskCalls.get());
    assertEquals(0, scrapi.getServer().getRequestCount());
  }

  @Test
  public void checksTheRowIDsOfALoadedDatasetBeforeAnyTaskRuns() throws Exception {
    Map<String, String> cases = new LinkedHashMap<>();
    cases.put(
        "{'data':[{'rowID':'row-0','columns':[]},{'rowID':'row-0','columns':[]}]}",
        "[StatsigAI] data[1] repeats rowID row-0; each record needs its own dataset row.");
    cases.put(
        "{'data':[{'columns':[{'name':'question','value':'q0'}]}]}",
        "[StatsigAI] data[0] needs a rowID when the eval names a dataset.");
    AtomicInteger taskCalls = new AtomicInteger();

    for (Map.Entry<String, String> entry : cases.entrySet()) {
      scrapi.enqueue(
          new MockResponse().setResponseCode(200).setBody(json(entry.getKey()).toString()));
      Eval<String, String, String> eval =
          local(
                  Eval.builder("dataset eval", goldenSet())
                      .task(
                          (input, hooks) -> {
                            taskCalls.incrementAndGet();
                            return input;
                          })
                      .scorer(args -> 1))
              .build();

      IllegalArgumentException error = assertThrows(IllegalArgumentException.class, eval::run);
      assertEquals(entry.getValue(), error.getMessage());
      assertTrue(takeRequest().getPath().startsWith(DATASETS_PATH));
    }

    assertEquals(0, taskCalls.get());
    assertNull(scrapi.takeRequest(200, TimeUnit.MILLISECONDS));
  }

  @Test
  public void failedDatasetRequestFailsTheRun() throws Exception {
    AtomicInteger taskCalls = new AtomicInteger();
    // A record with only an input has an Object expected value.
    Eval.Builder<String, String, Object> builder =
        local(
            Eval.builder(
                    "dataset eval",
                    StatsigDataset.of(
                        "missing", row -> EvalDataRecord.of(row.getColumns().get("question"))))
                .task(
                    (input, hooks) -> {
                      taskCalls.incrementAndGet();
                      return "";
                    })
                .scorer(args -> 1));

    scrapi.enqueue(
        new MockResponse().setResponseCode(404).setBody("{\"message\":\"Dataset not found\"}"));
    IllegalStateException notFound =
        assertThrows(IllegalStateException.class, () -> builder.build().run());
    assertTrue(
        notFound.getMessage(),
        notFound.getMessage().startsWith("[StatsigAI] Failed to load eval dataset 'missing': 404"));
    assertTrue(notFound.getMessage(), notFound.getMessage().contains("Dataset not found"));

    scrapi.enqueue(new MockResponse().setResponseCode(500));
    ExecutionException serverError =
        assertThrows(
            ExecutionException.class, () -> builder.build().runAsync().get(5, TimeUnit.SECONDS));
    assertTrue(serverError.getCause() instanceof IllegalStateException);
    assertTrue(
        serverError.getCause().getMessage(),
        serverError
            .getCause()
            .getMessage()
            .startsWith("[StatsigAI] Failed to load eval dataset 'missing': 500"));

    // A failure on a later page fails the run too.
    enqueueDatasetPage(0, 100);
    scrapi.enqueue(new MockResponse().setResponseCode(503));
    IllegalStateException laterPage =
        assertThrows(IllegalStateException.class, () -> builder.build().run());
    assertTrue(laterPage.getMessage(), laterPage.getMessage().contains("'missing': 503"));

    for (int i = 0; i < 4; i++) {
      assertTrue(takeRequest().getPath().startsWith(DATASETS_PATH + "/missing/rows?page="));
    }
    assertNull(scrapi.takeRequest(200, TimeUnit.MILLISECONDS));

    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    builder.datasetEndpoint("http://127.0.0.1:" + closedPort + DATASETS_PATH);
    UncheckedIOException unreachable =
        assertThrows(UncheckedIOException.class, () -> builder.build().run());
    assertTrue(
        unreachable.getMessage(),
        unreachable
            .getMessage()
            .startsWith(
                "[StatsigAI] Failed to load eval dataset 'missing': java.net.ConnectException"));
    ExecutionException asyncUnreachable =
        assertThrows(
            ExecutionException.class, () -> builder.build().runAsync().get(5, TimeUnit.SECONDS));
    assertTrue(asyncUnreachable.getCause() instanceof UncheckedIOException);

    assertEquals(0, taskCalls.get());
  }

  @Test
  public void runAsyncLoadsTheDatasetOnTheRunsExecutor() throws Exception {
    enqueueDatasetPage(0, 2);
    enqueueOk();
    ConcurrentLinkedQueue<Runnable> queued = new ConcurrentLinkedQueue<>();

    CompletableFuture<EvalResult<String, String, String>> future =
        local(
                Eval.builder("dataset eval", goldenSet())
                    .task((input, hooks) -> input.replace('q', 'a'))
                    .scorer(args -> args.getOutput().equals(args.getExpected())))
            .executor(queued::add)
            .build()
            .runAsync();

    // Nothing is loaded until the executor runs the load.
    assertFalse(future.isDone());
    assertEquals(1, queued.size());
    assertEquals(0, scrapi.getServer().getRequestCount());
    while (!future.isDone()) {
      Runnable next = queued.poll();
      assertNotNull("the run stalled", next);
      next.run();
    }

    EvalResult<String, String, String> result = future.get();
    assertEquals(2, result.getResults().size());
    assertEquals("row-1", result.getResults().get(1).getRowID());
    assertEquals(DATASETS_PATH + "/golden%20set/rows?page=1&limit=100", takeRequest().getPath());
    assertEquals("golden set", takeBody().get("dataset").asText());
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

  // Reads the question as the input, the answer as the expected value and the groups as the
  // category.
  private static StatsigDataset<String, String> goldenSet() {
    return StatsigDataset.of(
        "golden set",
        row ->
            EvalDataRecord.of(
                row.getColumns().get("question"), row.getColumns().get("answer"), row.getGroups()));
  }

  // A page of dataset rows from..to-1, each with a question, an answer and the group "math".
  private void enqueueDatasetPage(int from, int to) throws Exception {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = from; i < to; i++) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("rowID", "row-" + i);
      row.put("columns", Arrays.asList(column("question", "q" + i), column("answer", "a" + i)));
      row.put("groups", Collections.singletonList("math"));
      rows.add(row);
    }
    scrapi.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(JSON.writeValueAsString(Collections.singletonMap("data", rows))));
  }

  private static Map<String, String> column(String name, String value) {
    Map<String, String> column = new LinkedHashMap<>();
    column.put("name", name);
    column.put("value", value);
    return column;
  }

  private <I, O, E> Eval.Builder<I, O, E> local(Eval.Builder<I, O, E> builder) {
    return builder
        .apiKey(API_KEY)
        .endpoint(scrapi.url(SEND_RESULTS_PATH))
        .datasetEndpoint(scrapi.url(DATASETS_PATH));
  }

  private void enqueueOk() {
    scrapi.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
  }

  private RecordedRequest takeRequest() throws InterruptedException {
    RecordedRequest request = scrapi.takeRequest(5, TimeUnit.SECONDS);
    assertNotNull("expected a request", request);
    return request;
  }

  private JsonNode takeBody() throws Exception {
    return JSON.readTree(takeRequest().getBody().readUtf8());
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
