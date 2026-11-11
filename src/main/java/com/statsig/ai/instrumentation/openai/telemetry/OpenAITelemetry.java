package com.statsig.ai.instrumentation.openai.telemetry;

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
  private final SpanTelemetry.SpanEventLogger eventLogger;

  public OpenAITelemetry() {
    this(SpanTelemetry.statsigEventLogger());
  }

  OpenAITelemetry(SpanTelemetry.SpanEventLogger eventLogger) {
    this.eventLogger = eventLogger;
  }

  public <T> T trace(String name, String genAiType, SpanCallable<T> callable) throws Throwable {

    Span span = tracer.spanBuilder(name).setSpanKind(SpanKind.CLIENT).startSpan();

    SpanTelemetry telemetry =
        new SpanTelemetry(span, name, SpanTelemetry.DEFAULT_MAX_JSON_CHARS, eventLogger);

    telemetry.setAttribute(
        StatsigTraceConventions.ATTR_SPAN_TYPE, StatsigTraceConventions.SPAN_TYPE_GEN_AI);

    telemetry.setAttribute(StatsigTraceConventions.ATTR_GEN_AI_SPAN_TYPE, genAiType);

    // Tag the model span and its statsig::gen_ai event with the active prompt version, Statsig
    // context and environment, so the call can be attributed and graded.
    Context ctx = Context.current();
    setAttributes(telemetry, StatsigContextUtils.getPromptSpanAttrs(ctx));
    setAttributes(telemetry, StatsigContextUtils.getStatsigSpanAttrs(ctx));
    setAttributes(telemetry, StatsigContextUtils.getEnvironmentSpanAttrs(ctx));

    try (Scope scope = span.storeInContext(Context.current()).makeCurrent()) {
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

  private static void setAttributes(SpanTelemetry telemetry, Map<String, String> attrs) {
    for (Map.Entry<String, String> entry : attrs.entrySet()) {
      telemetry.setAttribute(entry.getKey(), entry.getValue());
    }
  }

  @FunctionalInterface
  public interface SpanCallable<T> {
    T call(SpanTelemetry st) throws Throwable;
  }
}
