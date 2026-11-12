package com.statsig.ai.trace;

import com.statsig.ai.trace.options.WrapOptions;
import com.statsig.ai.trace.singleton.OtelSingleton;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Records workflow and tool spans around your code, so the model calls made inside them appear as
 * their children in a trace.
 *
 * <p>Each call of a wrapped function runs in its own span, {@code invoke_workflow} or {@code
 * execute_tool}, and logs one {@code statsig::gen_ai} event. The span gets {@code
 * statsig.span.type=gen_ai}, {@code statsig.span.gen_ai.type} ({@code workflow} or {@code tool}),
 * the name as {@code statsig.gen_ai.workflow.name} or {@code statsig.gen_ai.tool.name}, the tool
 * type as {@code gen_ai.tool.type}, the custom attributes, and the user, Statsig context,
 * environment and prompt version attributes from the context. It's marked OK when the body returns,
 * or ERROR with the exception recorded when it throws. The exception is re-thrown unchanged.
 *
 * <p>When the body returns a {@link CompletionStage}, the span ends when the stage completes. For a
 * {@link CompletableFuture}, the wrapper returns a future that completes with the same result or
 * exception after the span has ended, like the chained promise in the Node SDK, so a caller can
 * {@code join()} it and then flush. Other {@code CompletionStage} types are returned as they are,
 * and a thread waiting on one can resume just before the span ends.
 *
 * <p>Model call spans from {@code OpenAIWrapper} are marked {@code statsig.span.llm_root=1} unless
 * they run inside a workflow span, which stands for the whole turn. {@link #startWorkflow(String,
 * Supplier)} marks its own workflow span as the root instead. Tool spans don't change the marking.
 */
public final class GenAISpans {
  private GenAISpans() {}

  private static final String TRACER_NAME = "com.statsig.ai.trace";
  private static final String WORKFLOW_SPAN_NAME = "invoke_workflow";
  private static final String TOOL_SPAN_NAME = "execute_tool";
  private static final String ATTR_WORKFLOW_NAME = "statsig.gen_ai.workflow.name";
  private static final String ATTR_TOOL_NAME = "statsig.gen_ai.tool.name";
  private static final String ATTR_TOOL_TYPE = "gen_ai.tool.type";

  // Tests replace it to read the statsig::gen_ai events.
  static volatile SpanTelemetry.SpanEventLogger eventLogger = SpanTelemetry.statsigEventLogger();

  // ----------------------------------------------------------------------
  // wrap
  // ----------------------------------------------------------------------

  /**
   * Returns a supplier that runs {@code fn} in a span each time it's called. See {@link
   * GenAISpans}.
   */
  public static <T> Supplier<T> wrap(WrapOptions options, Supplier<T> fn) {
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(fn, "fn");
    return () -> run(options, false, fn::get);
  }

  /**
   * Returns a function that runs {@code fn} in a span each time it's called. See {@link
   * GenAISpans}.
   */
  public static <A, R> Function<A, R> wrap(WrapOptions options, Function<A, R> fn) {
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(fn, "fn");
    return arg -> run(options, false, () -> fn.apply(arg));
  }

  /**
   * Returns a runnable that runs {@code fn} in a span each time it's called. See {@link
   * GenAISpans}.
   */
  public static Runnable wrap(WrapOptions options, Runnable fn) {
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(fn, "fn");
    return () ->
        run(
            options,
            false,
            () -> {
              fn.run();
              return null;
            });
  }

  /**
   * Returns a callable that runs {@code fn} in a span each time it's called. Exceptions {@code fn}
   * throws, checked or not, are recorded and re-thrown unchanged. See {@link GenAISpans}.
   */
  public static <T> Callable<T> wrapCallable(WrapOptions options, Callable<T> fn) {
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(fn, "fn");
    return () -> run(options, false, fn::call);
  }

  // ----------------------------------------------------------------------
  // startWorkflow
  // ----------------------------------------------------------------------

  /**
   * Runs {@code body} now in an {@code invoke_workflow} span marked {@code
   * statsig.span.llm_root=1}, and returns its result. Model calls inside it aren't marked. See
   * {@link GenAISpans}.
   */
  public static <T> T startWorkflow(String name, Supplier<T> body) {
    return startWorkflow(WrapOptions.workflow(name).build(), body);
  }

  /**
   * Runs {@code body} now in a root workflow span. See {@link #startWorkflow(String, Supplier)}.
   */
  public static void startWorkflow(String name, Runnable body) {
    startWorkflow(WrapOptions.workflow(name).build(), body);
  }

  /**
   * Runs {@code body} now in a root workflow span, with the user, activity ID and attributes from
   * {@code options}, and returns its result. See {@link #startWorkflow(String, Supplier)}.
   *
   * @param options workflow options, from {@link WrapOptions#workflow(String)}
   * @throws IllegalArgumentException if {@code options} are for a tool
   */
  public static <T> T startWorkflow(WrapOptions options, Supplier<T> body) {
    checkWorkflow(options);
    Objects.requireNonNull(body, "body");
    return run(options, true, body::get);
  }

  /**
   * Runs {@code body} now in a root workflow span, with the user, activity ID and attributes from
   * {@code options}. See {@link #startWorkflow(String, Supplier)}.
   *
   * @param options workflow options, from {@link WrapOptions#workflow(String)}
   * @throws IllegalArgumentException if {@code options} are for a tool
   */
  public static void startWorkflow(WrapOptions options, Runnable body) {
    checkWorkflow(options);
    Objects.requireNonNull(body, "body");
    run(
        options,
        true,
        () -> {
          body.run();
          return null;
        });
  }

  /**
   * Runs {@code body} now in a root workflow span, and returns its result. Exceptions {@code body}
   * throws, checked or not, are recorded and re-thrown unchanged. See {@link #startWorkflow(String,
   * Supplier)}.
   */
  public static <T> T startWorkflowCallable(String name, Callable<T> body) throws Exception {
    return startWorkflowCallable(WrapOptions.workflow(name).build(), body);
  }

  /**
   * Runs {@code body} now in a root workflow span, with the user, activity ID and attributes from
   * {@code options}, and returns its result. Exceptions {@code body} throws, checked or not, are
   * recorded and re-thrown unchanged. See {@link #startWorkflow(String, Supplier)}.
   *
   * @param options workflow options, from {@link WrapOptions#workflow(String)}
   * @throws IllegalArgumentException if {@code options} are for a tool
   */
  public static <T> T startWorkflowCallable(WrapOptions options, Callable<T> body)
      throws Exception {
    checkWorkflow(options);
    Objects.requireNonNull(body, "body");
    return run(options, true, body::call);
  }

  private static void checkWorkflow(WrapOptions options) {
    Objects.requireNonNull(options, "options");
    if (options.getType() != WrapOptions.Type.WORKFLOW) {
      throw new IllegalArgumentException(
          "startWorkflow takes workflow options, from WrapOptions.workflow(name)");
    }
  }

  // ----------------------------------------------------------------------
  // Span
  // ----------------------------------------------------------------------

  @FunctionalInterface
  private interface Body<T, E extends Throwable> {
    T call() throws E;
  }

  private static <T, E extends Throwable> T run(
      WrapOptions options, boolean llmRoot, Body<T, E> body) throws E {
    boolean isWorkflow = options.getType() == WrapOptions.Type.WORKFLOW;
    String spanName = isWorkflow ? WORKFLOW_SPAN_NAME : TOOL_SPAN_NAME;

    Context parent = Context.current();
    if (options.getUser() != null || options.getActivityID() != null) {
      parent =
          StatsigContextUtils.withStatsigContext(
              parent, options.getUser(), options.getActivityID());
    }
    // A workflow stands for the whole turn, so the model calls it makes aren't LLM roots.
    if (isWorkflow) {
      parent = parent.with(StatsigTraceConventions.CTX_GEN_AI_ROOT, spanName);
    }

    Span span =
        OtelSingleton.getTracerProviderStatic()
            .get(TRACER_NAME)
            .spanBuilder(spanName)
            .setParent(parent)
            .startSpan();
    Context spanCtx = parent.with(span);
    SpanTelemetry telemetry =
        new SpanTelemetry(span, spanName, SpanTelemetry.DEFAULT_MAX_JSON_CHARS, eventLogger);

    try (Scope ignored = spanCtx.makeCurrent()) {
      setAttributes(telemetry, options.getAttributes());
      if (llmRoot) {
        telemetry.setAttribute(
            StatsigTraceConventions.ATTR_SPAN_LLM_ROOT,
            StatsigTraceConventions.SPAN_LLM_ROOT_VALUE);
      }
      telemetry.setAttribute(
          StatsigTraceConventions.ATTR_SPAN_TYPE, StatsigTraceConventions.SPAN_TYPE_GEN_AI);
      setAttributes(telemetry, StatsigContextUtils.getUserSpanAttrs(spanCtx));
      setAttributes(telemetry, StatsigContextUtils.getStatsigSpanAttrs(spanCtx));
      setAttributes(telemetry, StatsigContextUtils.getEnvironmentSpanAttrs(spanCtx));
      // Workflow spans always get the template variables, as in Node; tool spans never do.
      setAttributes(telemetry, StatsigContextUtils.getPromptSpanAttrs(spanCtx, isWorkflow));
      setTypeAttributes(telemetry, options, isWorkflow);

      T result = body.call();
      if (result instanceof CompletionStage) {
        CompletionStage<?> chained =
            ((CompletionStage<?>) result)
                .whenComplete(
                    (value, error) -> {
                      if (error == null) {
                        telemetry.setStatus(StatusCode.OK);
                      } else {
                        telemetry.fail(unwrap(error));
                      }
                      end(telemetry, spanCtx, isWorkflow);
                    });
        // A plain CompletableFuture's chained future is a CompletableFuture too, so it can stand
        // in for the body's result. It completes after the span ends.
        if (result.getClass() == CompletableFuture.class) {
          @SuppressWarnings("unchecked")
          T chainedResult = (T) chained;
          return chainedResult;
        }
        return result;
      }
      telemetry.setStatus(StatusCode.OK);
      end(telemetry, spanCtx, isWorkflow);
      return result;
    } catch (Throwable t) {
      telemetry.fail(t);
      end(telemetry, spanCtx, isWorkflow);
      throw t;
    }
  }

  private static void setTypeAttributes(
      SpanTelemetry telemetry, WrapOptions options, boolean isWorkflow) {
    if (isWorkflow) {
      telemetry.setAttribute(ATTR_WORKFLOW_NAME, options.getName());
      telemetry.setAttribute(
          StatsigTraceConventions.ATTR_GEN_AI_SPAN_TYPE,
          StatsigTraceConventions.GEN_AI_SPAN_TYPE_WORKFLOW);
    } else {
      telemetry.setAttribute(ATTR_TOOL_NAME, options.getName());
      telemetry.setAttribute(ATTR_TOOL_TYPE, options.getToolType());
      telemetry.setAttribute(
          StatsigTraceConventions.ATTR_GEN_AI_SPAN_TYPE,
          StatsigTraceConventions.GEN_AI_SPAN_TYPE_TOOL);
    }
  }

  private static void end(SpanTelemetry telemetry, Context spanCtx, boolean isWorkflow) {
    // Read again, like Node, to record the prompt template variables rendered inside the span.
    setAttributes(telemetry, StatsigContextUtils.getPromptSpanAttrs(spanCtx, isWorkflow));
    telemetry.end(spanCtx);
  }

  private static void setAttributes(SpanTelemetry telemetry, Map<String, ?> attrs) {
    for (Map.Entry<String, ?> entry : attrs.entrySet()) {
      telemetry.setAttribute(entry.getKey(), entry.getValue());
    }
  }

  // A stage that fails because an earlier stage failed reports a CompletionException.
  private static Throwable unwrap(Throwable error) {
    if (error instanceof CompletionException && error.getCause() != null) {
      return error.getCause();
    }
    return error;
  }
}
