package com.statsig.ai.instrumentation.openai.telemetry;

import com.statsig.ai.instrumentation.openai.OpenAIWrapperConfig;
import com.statsig.ai.trace.SpanTelemetry;
import com.statsig.ai.trace.StatsigContextUtils;
import com.statsig.ai.trace.StatsigTraceConventions;
import com.statsig.ai.trace.singleton.OtelSingleton;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.Map;

public final class OpenAITelemetry {

  private final Tracer tracer =
      OtelSingleton.getTracerProviderStatic().get("com.statsig.ai.instrumentation.openai");
  private final OpenAIWrapperConfig config;
  private final SpanTelemetry.SpanEventLogger eventLogger;

  public OpenAITelemetry(OpenAIWrapperConfig config) {
    this(config, SpanTelemetry.statsigEventLogger());
  }

  OpenAITelemetry(OpenAIWrapperConfig config, SpanTelemetry.SpanEventLogger eventLogger) {
    this.config = config == null ? OpenAIWrapperConfig.builder().build() : config;
    this.eventLogger = eventLogger;
  }

  public <T> T trace(String name, String genAiType, SpanCallable<T> callable) throws Throwable {
    // A model call is the LLM root (statsig.span.llm_root) unless it runs inside one already,
    // such as a workflow span. Model calls made inside it then aren't roots either.
    Context ctx = Context.current();
    String llmRoot = ctx.get(StatsigTraceConventions.CTX_GEN_AI_ROOT);
    boolean isLLMRoot = llmRoot == null || llmRoot.isEmpty();
    if (isLLMRoot) {
      ctx = ctx.with(StatsigTraceConventions.CTX_GEN_AI_ROOT, name);
    }

    Span span = tracer.spanBuilder(name).setParent(ctx).setSpanKind(SpanKind.CLIENT).startSpan();

    SpanTelemetry telemetry = new SpanTelemetry(span, name, config.getMaxJsonChars(), eventLogger);

    // First, so the wrapper's own attributes win when a key is the same, as in Node.
    setAttributes(telemetry, config.getCustomAttributes());

    telemetry.setAttribute(
        StatsigTraceConventions.ATTR_SPAN_TYPE, StatsigTraceConventions.SPAN_TYPE_GEN_AI);

    telemetry.setAttribute(StatsigTraceConventions.ATTR_GEN_AI_SPAN_TYPE, genAiType);

    if (isLLMRoot) {
      telemetry.setAttribute(
          StatsigTraceConventions.ATTR_SPAN_LLM_ROOT, StatsigTraceConventions.SPAN_LLM_ROOT_VALUE);
    }

    // Tag the model span and its statsig::gen_ai event with the active prompt version, Statsig
    // context and environment, so the call can be attributed and graded.
    setAttributes(telemetry, StatsigContextUtils.getPromptSpanAttrs(ctx));
    setAttributes(telemetry, StatsigContextUtils.getStatsigSpanAttrs(ctx));
    setAttributes(telemetry, StatsigContextUtils.getEnvironmentSpanAttrs(ctx));

    try (Scope scope = span.storeInContext(ctx).makeCurrent()) {
      T result = callable.call(telemetry);
      telemetry.setStatus(StatusCode.OK);
      return result;
    } catch (Throwable t) {
      telemetry.fail(t);
      throw t;
    } finally {
      telemetry.end();
    }
  }

  private static void setAttributes(SpanTelemetry telemetry, Map<String, ?> attrs) {
    for (Map.Entry<String, ?> entry : attrs.entrySet()) {
      telemetry.setAttribute(entry.getKey(), entry.getValue());
    }
  }

  @FunctionalInterface
  public interface SpanCallable<T> {
    T call(SpanTelemetry st) throws Throwable;
  }
}
