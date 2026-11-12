package com.statsig.ai.trace;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Map;
import java.util.Objects;

/**
 * Stamps each span, when it starts, with the Statsig user ({@code statsig.user_id}, {@code
 * statsig.custom_ids}) and environment ({@code statsig.environment}) from its parent context, then
 * hands it to a delegate processor for export.
 */
public final class StatsigSpanProcessor implements SpanProcessor {
  private final SpanProcessor delegate;

  /** Batches spans to {@code exporter} with the default {@link BatchSpanProcessor} settings. */
  public StatsigSpanProcessor(SpanExporter exporter) {
    this(BatchSpanProcessor.builder(Objects.requireNonNull(exporter, "exporter")).build());
  }

  /**
   * Hands spans to {@code delegate}, for example a {@link BatchSpanProcessor} with custom settings.
   */
  public StatsigSpanProcessor(SpanProcessor delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  @Override
  public void onStart(Context parentContext, ReadWriteSpan span) {
    setAttributes(span, StatsigContextUtils.getUserSpanAttrs(parentContext));
    setAttributes(span, StatsigContextUtils.getEnvironmentSpanAttrs(parentContext));
    delegate.onStart(parentContext, span);
  }

  @Override
  public boolean isStartRequired() {
    return true;
  }

  @Override
  public void onEnd(ReadableSpan span) {
    delegate.onEnd(span);
  }

  @Override
  public boolean isEndRequired() {
    return delegate.isEndRequired();
  }

  @Override
  public CompletableResultCode shutdown() {
    return delegate.shutdown();
  }

  @Override
  public CompletableResultCode forceFlush() {
    return delegate.forceFlush();
  }

  private static void setAttributes(ReadWriteSpan span, Map<String, String> attrs) {
    for (Map.Entry<String, String> entry : attrs.entrySet()) {
      span.setAttribute(entry.getKey(), entry.getValue());
    }
  }
}
