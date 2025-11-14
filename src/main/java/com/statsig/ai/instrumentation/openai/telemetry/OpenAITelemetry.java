package com.statsig.ai.instrumentation.openai.telemetry;

import com.statsig.ai.trace.StatsigTraceConventions;
import com.statsig.ai.trace.singleton.OtelSingleton;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

public final class OpenAITelemetry {

  private final Tracer tracer =
      OtelSingleton.getTracerProviderStatic().get("com.statsig.ai.instrumentation.openai");

  public <T> T trace(String name, String genAiType, SpanCallable<T> callable) throws Throwable {

    Span span = tracer.spanBuilder(name).setSpanKind(SpanKind.CLIENT).startSpan();

    SpanTelemetry telemetry =
        new SpanTelemetry(
            span, name, SpanTelemetry.DEFAULT_MAX_JSON_CHARS, SpanTelemetry.statsigEventLogger());

    telemetry.setAttribute(
        StatsigTraceConventions.ATTR_SPAN_TYPE, StatsigTraceConventions.SPAN_TYPE_GEN_AI);

    telemetry.setAttribute(StatsigTraceConventions.ATTR_GEN_AI_SPAN_TYPE, genAiType);

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

  @FunctionalInterface
  public interface SpanCallable<T> {
    T call(SpanTelemetry st) throws Throwable;
  }
}
