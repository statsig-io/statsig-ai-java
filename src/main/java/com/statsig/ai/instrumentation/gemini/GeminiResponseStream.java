package com.statsig.ai.instrumentation.gemini;

import com.google.genai.JsonSerializable;
import com.google.genai.ResponseStream;
import com.statsig.ai.instrumentation.GenAIStreamTracker;
import java.util.Iterator;

/**
 * A traced {@code generateContentStream} response. Iterate it once and close it, like a {@link
 * ResponseStream}. Its span records the time to the first chunk, and ends when the stream has been
 * read to the end (with the response rebuilt from the chunks), fails, or is closed first.
 */
public final class GeminiResponseStream<T extends JsonSerializable>
    implements Iterable<T>, AutoCloseable {
  private final ResponseStream<T> delegate;
  private final GenAIStreamTracker<T> tracker;

  GeminiResponseStream(ResponseStream<T> delegate, GenAIStreamTracker<T> tracker) {
    this.delegate = delegate;
    this.tracker = tracker;
  }

  @Override
  public Iterator<T> iterator() {
    return tracker.track(delegate.iterator());
  }

  @Override
  public void close() {
    delegate.close();
    tracker.close();
  }
}
