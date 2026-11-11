package com.statsig.ai.instrumentation.openai.handlers;

import com.openai.core.http.AsyncStreamResponse;
import com.statsig.ai.instrumentation.GenAISpan;
import com.statsig.ai.instrumentation.openai.GenAICaptureOptions;
import io.opentelemetry.api.trace.StatusCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * An async stream whose model call span records the time to the first chunk, and ends when the
 * stream completes (marked OK, with the response rebuilt from the chunks), fails (marked ERROR), or
 * is closed first. Without a subscriber, the span ends when {@link #onCompleteFuture()} completes.
 */
final class TracedAsyncStreamResponse<T> implements AsyncStreamResponse<T> {
  private final AsyncStreamResponse<T> delegate;
  private final GenAISpan span;
  private final GenAICaptureOptions capture;
  private final List<T> chunks = new ArrayList<T>();
  private volatile boolean subscribed = false;
  private boolean finished = false;

  TracedAsyncStreamResponse(
      AsyncStreamResponse<T> delegate, GenAISpan span, GenAICaptureOptions capture) {
    this.delegate = delegate;
    this.span = span;
    this.capture = capture;
    CompletableFuture<Void> completion = delegate.onCompleteFuture();
    if (completion != null) {
      completion.whenComplete(
          (ignored, error) -> {
            if (!subscribed) {
              finish(error);
            }
          });
    }
  }

  @Override
  public AsyncStreamResponse<T> subscribe(Handler<? super T> handler) {
    subscribed = true;
    delegate.subscribe(traced(handler));
    return this;
  }

  @Override
  public AsyncStreamResponse<T> subscribe(Handler<? super T> handler, Executor executor) {
    subscribed = true;
    delegate.subscribe(traced(handler), executor);
    return this;
  }

  @Override
  public CompletableFuture<Void> onCompleteFuture() {
    return delegate.onCompleteFuture();
  }

  @Override
  public void close() {
    delegate.close();
    synchronized (this) {
      finished = true;
      span.end();
    }
  }

  private Handler<T> traced(Handler<? super T> handler) {
    return new Handler<T>() {
      @Override
      public void onNext(T chunk) {
        onChunk(chunk);
        handler.onNext(chunk);
      }

      @Override
      public void onComplete(Optional<Throwable> error) {
        finish(error.orElse(null));
        handler.onComplete(error);
      }
    };
  }

  private synchronized void onChunk(T chunk) {
    if (chunks.isEmpty()) {
      span.recordTimeToFirstToken();
    }
    chunks.add(chunk);
  }

  private synchronized void finish(Throwable error) {
    if (finished) {
      return;
    }
    finished = true;
    if (error != null) {
      span.fail(error);
    } else {
      span.setAttributesSafely(() -> TracedStreamResponse.streamAttributes(chunks, capture));
      span.getTelemetry().setStatus(StatusCode.OK);
    }
    span.end();
  }
}
