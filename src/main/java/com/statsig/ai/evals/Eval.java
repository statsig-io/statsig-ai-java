package com.statsig.ai.evals;

import com.statsig.ai.trace.GenAISpans;
import com.statsig.ai.trace.StatsigContextUtils;
import com.statsig.ai.trace.StatsigTraceConventions;
import com.statsig.ai.trace.options.WrapOptions;
import com.statsig.ai.trace.singleton.OtelSingleton;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * An offline eval: runs a task on every record of a dataset, grades each output with one or more
 * scorers, and sends the results to Statsig as a new run of the eval. The eval must already exist
 * in the Statsig console.
 *
 * <pre>{@code
 * EvalResult<String, String, String> result =
 *     Eval.builder("greeting", data)
 *         .task((input, hooks) -> "Hello " + input)
 *         .scorer(args -> args.getOutput().equals(args.getExpected()))
 *         .build()
 *         .run();
 * }</pre>
 *
 * <p>To run on an eval dataset from the console, pass a {@link StatsigDataset} instead of the data.
 *
 * <p>Records run concurrently, and so do the scorers for a record. Sending needs a Statsig Console
 * API key, from {@link Builder#apiKey(String)} or the {@code STATSIG_API_KEY} environment variable.
 *
 * <p>Tasks and scorers run with the Statsig environment {@code eval}, so their spans get {@code
 * statsig.environment=eval}. When tracing is initialized ({@code Otel.initialize}), each record
 * also gets its own trace: a root workflow span named after the eval, with the task's model calls
 * and a span for each scorer under it. Its IDs are in {@link EvalResultRecord#getTraceID()} and
 * {@link EvalResultRecord#getSpanID()}, and are sent with the results.
 *
 * @param <I> input type
 * @param <O> output type
 * @param <E> expected value type
 */
public final class Eval<I, O, E> {
  static final String API_KEY_ENV_VAR = "STATSIG_API_KEY";

  /** The name of a scorer added without one. */
  public static final String DEFAULT_SCORER_NAME = "Grader";

  // The Statsig environment that tasks and scorers run in.
  static final String EVAL_ENVIRONMENT = "eval";
  // How long to wait for the record spans to export before sending: the default export timeout
  // of the BatchSpanProcessor that Otel.initialize installs.
  static final long DEFAULT_FLUSH_TIMEOUT_MILLIS = 30_000;

  private final String name;
  // Exactly one of data and statsigDataset is set.
  private final Iterable<EvalDataRecord<I, E>> data;
  private final StatsigDataset<I, E> statsigDataset;
  private final String dataset;
  private final AsyncEvalTask<I, O> task;
  private final Map<String, EvalScorer<I, O, E>> scorers;
  private final Map<String, Object> parameters;
  private final String evalRunName;
  private final Function<List<EvalResultRecord<I, O, E>>, Map<String, ? extends Number>>
      summaryScorer;
  private final Executor executor;
  private final String apiKey;
  private final String endpoint;
  private final String datasetEndpoint;
  private final long flushTimeoutMillis;

  private Eval(Builder<I, O, E> builder, String apiKey) {
    this.name = builder.name;
    this.data = builder.data;
    this.statsigDataset = builder.statsigDataset;
    // Like Node's `options.dataset ?? data.name`.
    this.dataset =
        builder.dataset != null
            ? builder.dataset
            : statsigDataset != null ? statsigDataset.getName() : null;
    this.task = builder.task;
    this.scorers = Collections.unmodifiableMap(new LinkedHashMap<>(builder.scorers));
    this.parameters = Collections.unmodifiableMap(new LinkedHashMap<>(builder.parameters));
    this.evalRunName = builder.evalRunName;
    this.summaryScorer = builder.summaryScorer;
    this.executor = builder.executor;
    this.apiKey = apiKey;
    this.endpoint = builder.endpoint;
    this.datasetEndpoint = builder.datasetEndpoint;
    this.flushTimeoutMillis = builder.flushTimeoutMillis;
  }

  /**
   * Starts an eval named {@code name} (the eval's name in the console) on {@code data}. Pick the
   * task next with {@link TaskStep#task(EvalTask)} or {@link TaskStep#asyncTask(AsyncEvalTask)}.
   */
  public static <I, E> TaskStep<I, E> builder(String name, Iterable<EvalDataRecord<I, E>> data) {
    return new TaskStep<>(name, Objects.requireNonNull(data, "data"), null);
  }

  /**
   * Starts an eval named {@code name} on the rows of a Statsig eval dataset. The rows load when the
   * eval runs, with the eval's API key. The run names the dataset (see {@link
   * Builder#dataset(String)}) and each result keeps its row's ID, so the console compares runs on
   * the dataset row by row.
   */
  public static <I, E> TaskStep<I, E> builder(String name, StatsigDataset<I, E> dataset) {
    return new TaskStep<>(name, null, Objects.requireNonNull(dataset, "dataset"));
  }

  /**
   * Runs the eval, sends the results to Statsig, and returns them. A failed send is logged, not
   * thrown.
   *
   * @throws IllegalStateException if the eval's {@link StatsigDataset} can't be loaded because the
   *     API answers with a non-2xx status
   * @throws java.io.UncheckedIOException if the dataset can't be loaded because of a network error
   * @throws IllegalArgumentException if the eval names a dataset and a record has no row ID, or the
   *     same one as an earlier record. No task runs then.
   */
  public EvalResult<I, O, E> run() {
    try {
      return runAsync().join();
    } catch (CompletionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw e;
    }
  }

  /**
   * Like {@link #run()}, but returns at once with a future of the results. A {@link StatsigDataset}
   * loads on the run's executor. The future completes exceptionally when {@link #run()} would
   * throw.
   */
  public CompletableFuture<EvalResult<I, O, E>> runAsync() {
    ExecutorService ownExecutor = executor == null ? newDefaultExecutor() : null;
    try {
      Executor baseExecutor = executor != null ? executor : ownExecutor;
      // Tasks see the caller's OpenTelemetry context, as Node's async context carries it.
      Context callerContext = Context.current();
      CompletableFuture<EvalResult<I, O, E>> result;
      if (statsigDataset != null) {
        result =
            CompletableFuture.supplyAsync(
                    () -> statsigDataset.load(datasetEndpoint, apiKey),
                    callerContext.wrap(baseExecutor))
                .thenCompose(records -> runRecords(records, baseExecutor, callerContext));
      } else {
        List<EvalDataRecord<I, E>> records = new ArrayList<>();
        for (EvalDataRecord<I, E> record : data) {
          records.add(Objects.requireNonNull(record, "data must not contain null records"));
        }
        result = runRecords(records, baseExecutor, callerContext);
      }
      if (ownExecutor == null) {
        return result;
      }
      return result.whenComplete((ignored, error) -> ownExecutor.shutdown());
    } catch (RuntimeException | Error e) {
      if (ownExecutor != null) {
        ownExecutor.shutdown();
      }
      CompletableFuture<EvalResult<I, O, E>> failed = new CompletableFuture<>();
      failed.completeExceptionally(e);
      return failed;
    }
  }

  private CompletableFuture<EvalResult<I, O, E>> runRecords(
      List<EvalDataRecord<I, E>> records, Executor baseExecutor, Context callerContext) {
    if (dataset != null) {
      assertDatasetRowIDs(records);
    }
    // Without Otel.initialize there's no exporter, so records aren't traced.
    boolean traceRecords = OtelSingleton.isInitialized();
    Context evalContext =
        StatsigContextUtils.withStatsigEnvironment(callerContext, EVAL_ENVIRONMENT);
    if (traceRecords) {
      // Like Node's trace.deleteSpan: each record starts its own trace, even when the eval runs
      // inside another span. The other context values, such as the environment, are kept.
      evalContext = evalContext.with(Span.getInvalid());
    }
    List<CompletableFuture<RecordOutcome<O>>> outcomes = new ArrayList<>();
    for (int i = 0; i < records.size(); i++) {
      outcomes.add(runRecord(records.get(i), i, baseExecutor, evalContext, traceRecords));
    }
    return CompletableFuture.allOf(outcomes.toArray(new CompletableFuture<?>[0]))
        .thenApplyAsync(
            ignored -> finish(records, outcomes, traceRecords), callerContext.wrap(baseExecutor));
  }

  // Node's assertDatasetRowIDs, checked before any task runs: the API rejects a run on a dataset
  // whose results don't each name a different row of it.
  private static void assertDatasetRowIDs(List<? extends EvalDataRecord<?, ?>> records) {
    Set<String> seen = new HashSet<>();
    for (int index = 0; index < records.size(); index++) {
      String rowID = records.get(index).getRowID();
      if (rowID == null || rowID.isEmpty()) {
        throw new IllegalArgumentException(
            "[StatsigAI] data[" + index + "] needs a rowID when the eval names a dataset.");
      }
      if (!seen.add(rowID)) {
        throw new IllegalArgumentException(
            "[StatsigAI] data["
                + index
                + "] repeats rowID "
                + rowID
                + "; each record needs its own dataset row.");
      }
    }
  }

  // Like Node's Eval(): a failed task gives an error record with every score 0. The record runs
  // on an executor thread in evalContext, which has the eval environment.
  private CompletableFuture<RecordOutcome<O>> runRecord(
      EvalDataRecord<I, E> record,
      int recordIndex,
      Executor executor,
      Context evalContext,
      boolean traceRecords) {
    EvalHooks hooks = new EvalHooks(parameters, record.getCategories());
    AtomicReference<SpanContext> recordSpan = new AtomicReference<>(SpanContext.getInvalid());
    Supplier<CompletableFuture<RecordOutcome<O>>> runTaskAndScorers =
        () -> runTaskAndScorers(record, hooks, executor, traceRecords);
    Supplier<CompletableFuture<RecordOutcome<O>>> run =
        traceRecords
            ? () -> startRecordTrace(recordIndex, recordSpan, runTaskAndScorers)
            : runTaskAndScorers;
    return CompletableFuture.supplyAsync(run, evalContext.wrap(executor))
        .thenCompose(outcome -> outcome)
        .exceptionally(
            error -> {
              warn("Eval failed for input " + record.getInput(), error);
              return RecordOutcome.failed(zeroScores());
            })
        .thenApply(outcome -> outcome.withRecordSpan(recordSpan.get()));
  }

  /**
   * Like Node's startRecordTrace: runs {@code body} in a workflow span marked as the LLM root,
   * which ends when the future {@code body} returns completes. It runs in evalContext, which has no
   * valid span, so the span starts a new trace. Stores the span's context in {@code recordSpan}.
   */
  private <T> CompletableFuture<T> startRecordTrace(
      int recordIndex,
      AtomicReference<SpanContext> recordSpan,
      Supplier<CompletableFuture<T>> body) {
    WrapOptions.WorkflowBuilder options =
        WrapOptions.workflow(name)
            .setAttribute(StatsigTraceConventions.ATTR_EVAL_NAME, name)
            .setAttribute(StatsigTraceConventions.ATTR_EVAL_RECORD_INDEX, recordIndex);
    if (evalRunName != null) {
      options.setAttribute(StatsigTraceConventions.ATTR_EVAL_RUN_NAME, evalRunName);
    }
    return GenAISpans.startWorkflow(
        options.build(),
        () -> {
          recordSpan.set(Span.current().getSpanContext());
          return body.get();
        });
  }

  // Runs the task now, on this thread, then the scorers on executor threads. Those run in this
  // thread's context, which has the environment and, when traced, the record's span, wherever the
  // task's future completes.
  private CompletableFuture<RecordOutcome<O>> runTaskAndScorers(
      EvalDataRecord<I, E> record, EvalHooks hooks, Executor executor, boolean traceRecords) {
    Executor recordExecutor = Context.current().wrap(executor);
    // thenCompose on a completed future calls the task now, and turns a throw into a failed
    // future, as supplyAsync does.
    return CompletableFuture.completedFuture(record.getInput())
        .thenCompose(input -> callTask(input, hooks))
        .thenCompose(
            output ->
                scoreRecord(record, output, recordExecutor, traceRecords)
                    .thenApply(scores -> RecordOutcome.succeeded(output, scores)));
  }

  private CompletableFuture<O> callTask(I input, EvalHooks hooks) {
    CompletableFuture<O> output;
    try {
      output = task.run(input, hooks);
    } catch (Exception e) {
      throw new CompletionException(e);
    }
    if (output == null) {
      throw new NullPointerException("asyncTask returned a null CompletableFuture");
    }
    return output;
  }

  private CompletableFuture<Map<String, ScoreWithMetadata>> scoreRecord(
      EvalDataRecord<I, E> record, O output, Executor executor, boolean traceRecords) {
    EvalScorerArgs<I, O, E> args = new EvalScorerArgs<>(record, output);
    List<String> names = new ArrayList<>(scorers.keySet());
    List<CompletableFuture<ScoreWithMetadata>> scores = new ArrayList<>();
    for (String scorerName : names) {
      scores.add(runScorer(scorerName, scorers.get(scorerName), args, executor, traceRecords));
    }
    return CompletableFuture.allOf(scores.toArray(new CompletableFuture<?>[0]))
        .thenApply(
            ignored -> {
              Map<String, ScoreWithMetadata> byName = new LinkedHashMap<>();
              for (int i = 0; i < names.size(); i++) {
                byName.put(names.get(i), scores.get(i).join());
              }
              return byName;
            });
  }

  // Runs the scorer on an executor thread, in a span of its own when traced.
  private static <I, O, E> CompletableFuture<ScoreWithMetadata> runScorer(
      String scorerName,
      EvalScorer<I, O, E> scorer,
      EvalScorerArgs<I, O, E> args,
      Executor executor,
      boolean traceRecords) {
    Supplier<CompletableFuture<ScoreWithMetadata>> score = () -> scoreNow(scorerName, scorer, args);
    Supplier<CompletableFuture<ScoreWithMetadata>> run =
        traceRecords ? () -> startScorerSpan(scorerName, score) : score;
    return CompletableFuture.supplyAsync(run, executor).thenCompose(result -> result);
  }

  /**
   * Like Node's startScorerSpan: runs {@code body} in a child workflow span named after the scorer,
   * which ends when the future {@code body} returns completes.
   */
  private static <T> CompletableFuture<T> startScorerSpan(
      String scorerName, Supplier<CompletableFuture<T>> body) {
    WrapOptions options =
        WrapOptions.workflow(scorerName)
            .setAttribute(StatsigTraceConventions.ATTR_EVAL_SCORER_NAME, scorerName)
            .build();
    return GenAISpans.wrap(options, body).get();
  }

  // Like Node's runScorer: calls the scorer now, on this thread. A throw or an invalid score gives
  // 0 and a warning, so the future never fails.
  private static <I, O, E> CompletableFuture<ScoreWithMetadata> scoreNow(
      String scorerName, EvalScorer<I, O, E> scorer, EvalScorerArgs<I, O, E> args) {
    // thenApply on a completed future calls the scorer now, and turns a throw into a failed
    // future, as supplyAsync does.
    return CompletableFuture.completedFuture(args)
        .thenApply(scorerArgs -> callScorer(scorer, scorerArgs))
        .thenCompose(Eval::awaitIfStage)
        .handle(
            (rawScore, error) -> {
              if (error != null) {
                warn("Scorer '" + scorerName + "' failed for input " + args.getInput(), error);
                return zeroScore();
              }
              ScoreWithMetadata score = normalizeScore(rawScore);
              if (score == null) {
                warn(
                    "Scorer '"
                        + scorerName
                        + "' failed for input "
                        + args.getInput()
                        + ": returned invalid type "
                        + (rawScore == null ? "null" : rawScore.getClass().getName())
                        + ". Expected a Number, a Boolean or a ScoreWithMetadata.",
                    null);
                return zeroScore();
              }
              return score;
            });
  }

  private static <I, O, E> Object callScorer(
      EvalScorer<I, O, E> scorer, EvalScorerArgs<I, O, E> args) {
    try {
      return scorer.score(args);
    } catch (Exception e) {
      throw new CompletionException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private static CompletionStage<Object> awaitIfStage(Object rawScore) {
    return rawScore instanceof CompletionStage
        ? (CompletionStage<Object>) rawScore
        : CompletableFuture.completedFuture(rawScore);
  }

  /** Node's normalizeScoreValue: true is 1, false is 0. Null for anything that isn't a score. */
  static ScoreWithMetadata normalizeScore(Object rawScore) {
    if (rawScore instanceof ScoreWithMetadata) {
      return (ScoreWithMetadata) rawScore;
    }
    if (rawScore instanceof Boolean) {
      return ScoreWithMetadata.of((Boolean) rawScore, null);
    }
    if (rawScore instanceof Number) {
      return ScoreWithMetadata.of(((Number) rawScore).doubleValue(), null);
    }
    return null;
  }

  private EvalResult<I, O, E> finish(
      List<EvalDataRecord<I, E>> records,
      List<CompletableFuture<RecordOutcome<O>>> outcomes,
      boolean traceRecords) {
    List<EvalResultRecord<I, O, E>> results = new ArrayList<>();
    List<EvalResultsSender.SentRecord> sent = new ArrayList<>();
    boolean anyError = false;
    for (int i = 0; i < records.size(); i++) {
      EvalDataRecord<I, E> record = records.get(i);
      RecordOutcome<O> outcome = outcomes.get(i).join();
      Map<String, Double> scores = new LinkedHashMap<>();
      for (Map.Entry<String, ScoreWithMetadata> entry : outcome.scores.entrySet()) {
        scores.put(entry.getKey(), entry.getValue().getScore());
      }
      results.add(
          new EvalResultRecord<>(
              record,
              outcome.output,
              outcome.error,
              Collections.unmodifiableMap(scores),
              outcome.traceID,
              outcome.spanID));
      sent.add(
          new EvalResultsSender.SentRecord(
              record,
              outcome.output,
              outcome.error,
              outcome.scores,
              outcome.traceID,
              outcome.spanID));
      anyError |= outcome.error;
    }
    List<EvalResultRecord<I, O, E>> unmodifiableResults = Collections.unmodifiableList(results);
    Map<String, Double> summaryScores = runSummaryScorer(unmodifiableResults);
    if (traceRecords) {
      flushRecordSpans();
    }
    EvalResultsSender.send(
        endpoint, name, apiKey, sent, evalRunName, dataset, summaryScores, parameters);
    return new EvalResult<>(unmodifiableResults, anyError, summaryScores);
  }

  // Like Node, exports the record spans before sending the results, so they aren't lost if the
  // process exits right after the eval. Runs on an executor thread, never runAsync's caller, and
  // waits at most flushTimeoutMillis. A failure or a timeout is logged, and the results are still
  // sent.
  private void flushRecordSpans() {
    CompletableResultCode flush;
    try {
      flush = OtelSingleton.flushInstance().join(flushTimeoutMillis, TimeUnit.MILLISECONDS);
    } catch (RuntimeException e) {
      warn("Failed to flush eval spans", e);
      return;
    }
    if (!flush.isDone()) {
      warn("Timed out flushing eval spans after " + flushTimeoutMillis + " ms", null);
    } else if (!flush.isSuccess()) {
      warn("Failed to flush eval spans", flush.getFailureThrowable());
    }
  }

  // Like Node's runSummaryScorer: a throw gives no summary scores and a warning.
  private Map<String, Double> runSummaryScorer(List<EvalResultRecord<I, O, E>> results) {
    if (summaryScorer == null) {
      return null;
    }
    Map<String, ? extends Number> summary;
    try {
      summary = summaryScorer.apply(results);
    } catch (RuntimeException e) {
      warn("Summary scorer failed", e);
      return null;
    }
    if (summary == null) {
      return null;
    }
    Map<String, Double> scores = new LinkedHashMap<>();
    for (Map.Entry<String, ? extends Number> entry : summary.entrySet()) {
      Number value = entry.getValue();
      scores.put(entry.getKey(), value == null ? null : value.doubleValue());
    }
    return Collections.unmodifiableMap(scores);
  }

  private Map<String, ScoreWithMetadata> zeroScores() {
    Map<String, ScoreWithMetadata> scores = new LinkedHashMap<>();
    for (String scorerName : this.scorers.keySet()) {
      scores.put(scorerName, zeroScore());
    }
    return scores;
  }

  private static ScoreWithMetadata zeroScore() {
    return ScoreWithMetadata.of(0.0, null);
  }

  private static void warn(String message, Throwable error) {
    Throwable cause = unwrap(error);
    System.err.println("[StatsigAI] " + message + (cause == null ? "" : ": " + cause));
    if (cause != null) {
      cause.printStackTrace();
    }
  }

  private static Throwable unwrap(Throwable error) {
    Throwable current = error;
    while ((current instanceof CompletionException || current instanceof ExecutionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  // Like Python's ThreadPoolExecutor default: min(32, processors + 4) threads, since tasks and
  // scorers usually wait on model calls.
  private static ExecutorService newDefaultExecutor() {
    int threads = Math.min(32, Runtime.getRuntime().availableProcessors() + 4);
    AtomicInteger count = new AtomicInteger();
    ThreadFactory factory =
        runnable -> {
          Thread thread = new Thread(runnable, "statsig-eval-" + count.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        };
    return Executors.newFixedThreadPool(threads, factory);
  }

  private static final class RecordOutcome<O> {
    final O output;
    final boolean error;
    final Map<String, ScoreWithMetadata> scores;
    // The record's trace and root span. Null when the record isn't traced.
    final String traceID;
    final String spanID;

    private RecordOutcome(
        O output,
        boolean error,
        Map<String, ScoreWithMetadata> scores,
        String traceID,
        String spanID) {
      this.output = output;
      this.error = error;
      this.scores = scores;
      this.traceID = traceID;
      this.spanID = spanID;
    }

    static <O> RecordOutcome<O> succeeded(O output, Map<String, ScoreWithMetadata> scores) {
      return new RecordOutcome<>(output, false, scores, null, null);
    }

    static <O> RecordOutcome<O> failed(Map<String, ScoreWithMetadata> zeroScores) {
      return new RecordOutcome<>(null, true, zeroScores, null, null);
    }

    // Like Node's getActiveSpanIDs: no IDs unless the span is valid.
    RecordOutcome<O> withRecordSpan(SpanContext span) {
      if (!span.isValid()) {
        return this;
      }
      return new RecordOutcome<>(output, error, scores, span.getTraceId(), span.getSpanId());
    }
  }

  /**
   * Returned by {@link Eval#builder(String, Iterable)} and {@link Eval#builder(String,
   * StatsigDataset)}: picks the task.
   */
  public static final class TaskStep<I, E> {
    private final String name;
    private final Iterable<EvalDataRecord<I, E>> data;
    private final StatsigDataset<I, E> statsigDataset;

    private TaskStep(
        String name, Iterable<EvalDataRecord<I, E>> data, StatsigDataset<I, E> statsigDataset) {
      this.name = Objects.requireNonNull(name, "name");
      this.data = data;
      this.statsigDataset = statsigDataset;
    }

    /** The task that turns each record's input into an output. */
    public <O> Builder<I, O, E> task(EvalTask<I, O> task) {
      Objects.requireNonNull(task, "task");
      return new Builder<>(
          this, (input, hooks) -> CompletableFuture.completedFuture(task.run(input, hooks)));
    }

    /** Like {@link #task(EvalTask)}, for a task that returns a {@code CompletableFuture}. */
    public <O> Builder<I, O, E> asyncTask(AsyncEvalTask<I, O> task) {
      Objects.requireNonNull(task, "task");
      return new Builder<>(this, task);
    }
  }

  /** Sets the scorers and the optional settings of an {@link Eval}. */
  public static final class Builder<I, O, E> {
    private final String name;
    private final Iterable<EvalDataRecord<I, E>> data;
    private final StatsigDataset<I, E> statsigDataset;
    private final AsyncEvalTask<I, O> task;
    private final Map<String, EvalScorer<I, O, E>> scorers = new LinkedHashMap<>();
    private final Map<String, Object> parameters = new LinkedHashMap<>();
    private String evalRunName;
    private String dataset;
    private Function<List<EvalResultRecord<I, O, E>>, Map<String, ? extends Number>> summaryScorer;
    private Executor executor;
    private String apiKey;
    private String endpoint = EvalResultsSender.DEFAULT_ENDPOINT;
    private String datasetEndpoint = StatsigDataset.DEFAULT_ENDPOINT;
    private Function<String, String> getenv = System::getenv;
    private long flushTimeoutMillis = DEFAULT_FLUSH_TIMEOUT_MILLIS;

    private Builder(TaskStep<I, E> step, AsyncEvalTask<I, O> task) {
      this.name = step.name;
      this.data = step.data;
      this.statsigDataset = step.statsigDataset;
      this.task = task;
    }

    /** Adds a scorer named {@value Eval#DEFAULT_SCORER_NAME}. */
    public Builder<I, O, E> scorer(EvalScorer<I, O, E> scorer) {
      return scorer(DEFAULT_SCORER_NAME, scorer);
    }

    /**
     * Adds a scorer. Its scores are reported under {@code name}.
     *
     * @throws IllegalArgumentException if a scorer already has this name
     */
    public Builder<I, O, E> scorer(String name, EvalScorer<I, O, E> scorer) {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(scorer, "scorer");
      if (scorers.containsKey(name)) {
        throw new IllegalArgumentException("[StatsigAI] Eval already has a scorer named " + name);
      }
      scorers.put(name, scorer);
      return this;
    }

    /** Adds each scorer in {@code scorers} under its key, in the map's order. */
    public Builder<I, O, E> scorers(Map<String, ? extends EvalScorer<I, O, E>> scorers) {
      Objects.requireNonNull(scorers, "scorers");
      for (Map.Entry<String, ? extends EvalScorer<I, O, E>> entry : scorers.entrySet()) {
        scorer(entry.getKey(), entry.getValue());
      }
      return this;
    }

    /**
     * Adds a parameter. Tasks read it from {@link EvalHooks#getParameter(String)}, and it is sent
     * with the run (as JSON when it isn't a string).
     */
    public Builder<I, O, E> parameter(String name, Object value) {
      parameters.put(Objects.requireNonNull(name, "name"), value);
      return this;
    }

    /** Adds each entry of {@code parameters}. See {@link #parameter(String, Object)}. */
    public Builder<I, O, E> parameters(Map<String, ?> parameters) {
      Objects.requireNonNull(parameters, "parameters");
      for (Map.Entry<String, ?> entry : parameters.entrySet()) {
        parameter(entry.getKey(), entry.getValue());
      }
      return this;
    }

    /** The name of this run in the console. Defaults to a numbered name. */
    public Builder<I, O, E> evalRunName(String evalRunName) {
      this.evalRunName = evalRunName;
      return this;
    }

    /**
     * The name or ID of the Statsig eval dataset the data came from, sent with the run. The console
     * compares runs on the same dataset row by row, so every record then needs the ID of its row,
     * from {@link EvalDataRecord#withRowID(String)}, and no two records may share one; the run
     * fails before any task runs otherwise. Defaults to the dataset's name with {@link
     * Eval#builder(String, StatsigDataset)}, and to none otherwise.
     */
    public Builder<I, O, E> dataset(String dataset) {
      this.dataset = dataset;
      return this;
    }

    /**
     * Computes summary scores, such as a pass rate, from the finished results. They are returned in
     * {@link EvalResult#getSummaryScores()} and sent with the run. If it throws, the run has no
     * summary scores and a warning is logged.
     */
    public Builder<I, O, E> summaryScores(
        Function<List<EvalResultRecord<I, O, E>>, Map<String, ? extends Number>> summaryScorer) {
      this.summaryScorer = summaryScorer;
      return this;
    }

    /**
     * Runs tasks and scorers on {@code executor}. By default each run uses its own pool of min(32,
     * processors + 4) threads, shut down when the run finishes.
     */
    public Builder<I, O, E> executor(Executor executor) {
      this.executor = executor;
      return this;
    }

    /** The Statsig Console API key to send results with. Defaults to {@code STATSIG_API_KEY}. */
    public Builder<I, O, E> apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    // The send_results URL, without the eval name. For tests.
    Builder<I, O, E> endpoint(String endpoint) {
      this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
      return this;
    }

    // The eval_datasets URL, without the dataset name. For tests.
    Builder<I, O, E> datasetEndpoint(String datasetEndpoint) {
      this.datasetEndpoint = Objects.requireNonNull(datasetEndpoint, "datasetEndpoint");
      return this;
    }

    // Reads environment variables. For tests.
    Builder<I, O, E> getenv(Function<String, String> getenv) {
      this.getenv = Objects.requireNonNull(getenv, "getenv");
      return this;
    }

    // How long to wait for the record spans to export before sending. For tests.
    Builder<I, O, E> flushTimeout(long timeout, TimeUnit unit) {
      this.flushTimeoutMillis = unit.toMillis(timeout);
      return this;
    }

    /**
     * @throws IllegalStateException if there is no scorer, or no API key from {@link
     *     #apiKey(String)} or {@code STATSIG_API_KEY}
     */
    public Eval<I, O, E> build() {
      if (scorers.isEmpty()) {
        throw new IllegalStateException("[StatsigAI] Eval needs at least one scorer.");
      }
      String key = isSet(apiKey) ? apiKey : getenv.apply(API_KEY_ENV_VAR);
      if (!isSet(key)) {
        throw new IllegalStateException(
            "[StatsigAI] Missing Statsig Console API key. Set the STATSIG_API_KEY environment"
                + " variable or call apiKey() with your Statsig Console API key.");
      }
      return new Eval<>(this, key);
    }

    private static boolean isSet(String value) {
      return value != null && !value.isEmpty();
    }
  }
}
