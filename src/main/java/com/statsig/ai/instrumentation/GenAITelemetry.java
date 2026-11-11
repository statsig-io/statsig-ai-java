package com.statsig.ai.instrumentation;

import com.statsig.ai.instrumentation.openai.GenAICaptureOptions;
import com.statsig.ai.instrumentation.openai.OpenAIWrapperConfig;
import com.statsig.ai.trace.SpanTelemetry;
import com.statsig.ai.trace.StatsigContextUtils;
import com.statsig.ai.trace.StatsigTraceConventions;
import com.statsig.ai.trace.singleton.OtelSingleton;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Starts and ends model call spans for the provider wrappers, like {@code startGenAISpan} and
 * {@code traceGenAICall} in the Node SDK, so every provider's spans are set up the same way.
 *
 * <p>Each span is a {@code CLIENT} span with the config's custom attributes, the provider's request
 * attributes, {@code statsig.span.type=gen_ai}, and the prompt version, Statsig context and
 * environment from the context. A model call is the LLM root ({@code statsig.span.llm_root=1})
 * unless it runs inside one already, such as a workflow span, and model calls made inside it then
 * aren't roots either.
 */
public final class GenAITelemetry {
  private final String instrumentationName;
  private final OpenAIWrapperConfig config;
  private final SpanTelemetry.SpanEventLogger eventLogger;

  public GenAITelemetry(String instrumentationName, OpenAIWrapperConfig config) {
    this(instrumentationName, config, SpanTelemetry.statsigEventLogger());
  }

  /**
   * Like {@link #GenAITelemetry(String, OpenAIWrapperConfig)}, logging each span's {@code
   * statsig::gen_ai} event with {@code eventLogger}.
   */
  public GenAITelemetry(
      String instrumentationName,
      OpenAIWrapperConfig config,
      SpanTelemetry.SpanEventLogger eventLogger) {
    this.instrumentationName = instrumentationName;
    this.config = config == null ? OpenAIWrapperConfig.builder().build() : config;
    this.eventLogger = eventLogger;
  }

  public GenAICaptureOptions getCaptureOptions() {
    return config.getCaptureOptions();
  }

  /** Starts a model call span in the current context. See {@link GenAITelemetry}. */
  public GenAISpan startSpan(String spanName, Map<String, ?> requestAttributes) {
    Context ctx = Context.current();
    String llmRoot = ctx.get(StatsigTraceConventions.CTX_GEN_AI_ROOT);
    boolean isLLMRoot = llmRoot == null || llmRoot.isEmpty();
    if (isLLMRoot) {
      ctx = ctx.with(StatsigTraceConventions.CTX_GEN_AI_ROOT, spanName);
    }

    // Later entries win, so the wrapper's own attributes replace custom ones with the same key.
    Map<String, Object> attributes = new LinkedHashMap<String, Object>();
    attributes.putAll(config.getCustomAttributes());
    attributes.putAll(requestAttributes);
    attributes.put(
        StatsigTraceConventions.ATTR_SPAN_TYPE, StatsigTraceConventions.SPAN_TYPE_GEN_AI);
    if (isLLMRoot) {
      attributes.put(
          StatsigTraceConventions.ATTR_SPAN_LLM_ROOT, StatsigTraceConventions.SPAN_LLM_ROOT_VALUE);
    }
    // The prompt version, Statsig context and environment let the call be attributed and graded.
    attributes.putAll(StatsigContextUtils.getPromptSpanAttrs(ctx));
    attributes.putAll(StatsigContextUtils.getStatsigSpanAttrs(ctx));
    attributes.putAll(StatsigContextUtils.getEnvironmentSpanAttrs(ctx));

    // Read when the span starts, so a client wrapped before Otel.initialize still exports spans.
    Tracer tracer = OtelSingleton.getTracerProviderStatic().get(instrumentationName);
    Span span =
        tracer.spanBuilder(spanName).setParent(ctx).setSpanKind(SpanKind.CLIENT).startSpan();
    SpanTelemetry telemetry =
        new SpanTelemetry(span, spanName, config.getMaxJsonChars(), eventLogger);
    telemetry.setAttributes(attributes);
    return new GenAISpan(telemetry, span.storeInContext(ctx));
  }

  /**
   * Runs a blocking provider call in a model call span, and returns its result. When the call
   * returns, the span records the time to the response, is marked OK, gets the attributes {@code
   * onResult} reads from the result, and ends. When it throws, the span records the exception, is
   * marked ERROR and ends, and the exception is re-thrown unchanged.
   */
  public <T> T trace(
      String spanName,
      Map<String, ?> requestAttributes,
      ProviderCall<T> call,
      ResultAttributes<T> onResult)
      throws Throwable {
    GenAISpan span = startSpan(spanName, requestAttributes);
    T result;
    try (Scope ignored = span.getContext().makeCurrent()) {
      result = call.call();
    } catch (Throwable t) {
      span.fail(t);
      span.end();
      throw t;
    }
    T value = result;
    span.recordTimeToFirstToken();
    span.getTelemetry().setStatus(StatusCode.OK);
    span.setAttributesSafely(() -> onResult.attributes(value));
    span.end();
    return value;
  }

  /**
   * Runs an async provider call in a model call span, and returns a future that completes with the
   * call's result or exception after the span has ended, so a caller can {@code join()} it and then
   * flush. The span ends like {@link #trace}'s when the call's future completes.
   */
  public <T> CompletableFuture<T> traceAsync(
      String spanName,
      Map<String, ?> requestAttributes,
      ProviderCall<CompletableFuture<T>> call,
      ResultAttributes<T> onResult)
      throws Throwable {
    GenAISpan span = startSpan(spanName, requestAttributes);
    CompletableFuture<T> future;
    try (Scope ignored = span.getContext().makeCurrent()) {
      future = call.call();
    } catch (Throwable t) {
      span.fail(t);
      span.end();
      throw t;
    }
    if (future == null) {
      span.end();
      return null;
    }
    return future.whenComplete(
        (value, error) -> {
          if (error != null) {
            span.fail(unwrap(error));
          } else {
            span.recordTimeToFirstToken();
            span.getTelemetry().setStatus(StatusCode.OK);
            span.setAttributesSafely(() -> onResult.attributes(value));
          }
          span.end();
        });
  }

  // A stage that fails because an earlier stage failed reports a CompletionException.
  private static Throwable unwrap(Throwable error) {
    if (error instanceof CompletionException && error.getCause() != null) {
      return error.getCause();
    }
    return error;
  }

  @FunctionalInterface
  public interface ProviderCall<T> {
    T call() throws Throwable;
  }

  @FunctionalInterface
  public interface ResultAttributes<T> {
    Map<String, ?> attributes(T result);
  }
}
