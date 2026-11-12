package com.statsig.ai.instrumentation.openai.handlers;

import com.openai.core.http.AsyncStreamResponse;
import com.statsig.ai.instrumentation.GenAISpan;
import com.statsig.ai.instrumentation.GenAIStreamTracker;
import com.statsig.ai.instrumentation.openai.GenAICaptureOptions;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * An async OpenAI stream tracked in its model call span. The span ends when the stream completes,
 * fails or is closed, or, without a subscriber, when {@link #onCompleteFuture()} completes. See
 * {@link GenAIStreamTracker}.
 */
final class TracedAsyncStreamResponse<T> implements AsyncStreamResponse<T> {
  private final AsyncStreamResponse<T> delegate;
  private final GenAIStreamTracker<T> tracker;
  private volatile boolean subscribed = false;

  TracedAsyncStreamResponse(
      AsyncStreamResponse<T> delegate, GenAISpan span, GenAICaptureOptions capture) {
    this.delegate = delegate;
    this.tracker =
        new GenAIStreamTracker<T>(
            span, chunks -> TracedStreamResponse.streamAttributes(chunks, capture));
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
    delegate.subscribe(tracked(handler));
    return this;
  }

  @Override
  public AsyncStreamResponse<T> subscribe(Handler<? super T> handler, Executor executor) {
    subscribed = true;
    delegate.subscribe(tracked(handler), executor);
    return this;
  }

  @Override
  public CompletableFuture<Void> onCompleteFuture() {
    return delegate.onCompleteFuture();
  }

  @Override
  public void close() {
    delegate.close();
    tracker.close();
  }

  private Handler<T> tracked(Handler<? super T> handler) {
    return new Handler<T>() {
      @Override
      public void onNext(T chunk) {
        tracker.onChunk(chunk);
        handler.onNext(chunk);
      }

      @Override
      public void onComplete(Optional<Throwable> error) {
        finish(error.orElse(null));
        handler.onComplete(error);
      }
    };
  }

  private void finish(Throwable error) {
    if (error != null) {
      tracker.fail(error);
    } else {
      tracker.complete();
    }
  }
}
