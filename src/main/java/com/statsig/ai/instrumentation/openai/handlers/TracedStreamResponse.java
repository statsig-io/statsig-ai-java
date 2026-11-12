package com.statsig.ai.instrumentation.openai.handlers;

import com.openai.core.http.StreamResponse;
import com.statsig.ai.instrumentation.GenAISpan;
import com.statsig.ai.instrumentation.GenAIStreamTracker;
import com.statsig.ai.instrumentation.openai.GenAICaptureOptions;
import com.statsig.ai.instrumentation.openai.telemetry.OpenAIAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** A blocking OpenAI stream tracked in its model call span. See {@link GenAIStreamTracker}. */
final class TracedStreamResponse<T> implements StreamResponse<T> {
  private final StreamResponse<T> delegate;
  private final GenAIStreamTracker<T> tracker;

  TracedStreamResponse(StreamResponse<T> delegate, GenAISpan span, GenAICaptureOptions capture) {
    this.delegate = delegate;
    this.tracker = new GenAIStreamTracker<T>(span, chunks -> streamAttributes(chunks, capture));
  }

  @Override
  public Stream<T> stream() {
    return tracker.track(delegate.stream());
  }

  @Override
  public void close() {
    delegate.close();
    tracker.close();
  }

  static Map<String, Object> streamAttributes(List<?> chunks, GenAICaptureOptions capture) {
    List<Map<String, Object>> plain = new ArrayList<Map<String, Object>>();
    for (Object chunk : chunks) {
      plain.add(OpenAIAttributes.toPlainMap(chunk));
    }
    return OpenAIAttributes.streamAttributes(plain, capture);
  }
}
