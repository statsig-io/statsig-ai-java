package com.statsig.ai.instrumentation;

import com.statsig.ai.trace.SpanTelemetry;
import io.opentelemetry.context.Context;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A model call span started by {@link GenAITelemetry#startSpan}. Run the provider call in {@link
 * #getContext()}, so spans the call starts become its children, then end it with {@link #end()}.
 * Streams and async calls can end it on another thread.
 */
public final class GenAISpan {
  private final SpanTelemetry telemetry;
  private final Context context;

  GenAISpan(SpanTelemetry telemetry, Context context) {
    this.telemetry = telemetry;
    this.context = context;
  }

  public SpanTelemetry getTelemetry() {
    return telemetry;
  }

  /** The context with this span current, and the Statsig context it was started in. */
  public Context getContext() {
    return context;
  }

  /**
   * Sets the attributes from {@code attributes}. A failure to read them is logged, not thrown, so
   * the provider's result still reaches the caller.
   */
  public void setAttributesSafely(Supplier<? extends Map<String, ?>> attributes) {
    try {
      telemetry.setAttributes(attributes.get());
    } catch (RuntimeException e) {
      System.err.println("[StatsigAI] Failed to record Gen AI response attributes: " + e);
    }
  }

  /**
   * Records the time since the span started as {@code
   * statsig.gen_ai.server.time_to_first_token_ms}.
   */
  public void recordTimeToFirstToken() {
    telemetry.recordTimeToFirstToken();
  }

  /** Records {@code error} and marks the span as failed. */
  public void fail(Throwable error) {
    telemetry.fail(error);
  }

  /** Ends the span and logs its {@code statsig::gen_ai} event. Does nothing after the first. */
  public void end() {
    telemetry.end(context);
  }
}
