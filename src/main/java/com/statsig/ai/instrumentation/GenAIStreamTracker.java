package com.statsig.ai.instrumentation;

import io.opentelemetry.api.trace.StatusCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Tracks a streamed provider response in its model call span, like Node's {@code TelemetryStream},
 * for the provider wrappers' stream types. The span records the time to the first chunk, and ends
 * once:
 *
 * <ul>
 *   <li>the stream has been read to the end: marked OK, with the attributes {@code onComplete}
 *       reads from all the chunks
 *   <li>reading it fails: marked ERROR. An exception from the caller's own code doesn't count.
 *   <li>it's closed first: ended without a response or status
 * </ul>
 */
public final class GenAIStreamTracker<T> {
  private final GenAISpan span;
  private final Function<List<T>, Map<String, ?>> onComplete;
  private final List<T> chunks = new ArrayList<T>();
  private boolean finished = false;

  /**
   * @param onComplete reads the response attributes from all the chunks
   */
  public GenAIStreamTracker(GenAISpan span, Function<List<T>, Map<String, ?>> onComplete) {
    this.span = span;
    this.onComplete = onComplete;
  }

  /** Returns {@code source}, tracked. Closing the returned stream closes the tracker. */
  public Stream<T> track(Stream<T> source) {
    Spliterator<T> chunks = source.spliterator();
    Spliterator<T> tracked =
        new Spliterators.AbstractSpliterator<T>(Long.MAX_VALUE, Spliterator.ORDERED) {
          @Override
          public boolean tryAdvance(Consumer<? super T> action) {
            return advance(chunks, action);
          }
        };
    return StreamSupport.stream(tracked, false).onClose(this::close);
  }

  /** Returns {@code source}, tracked. */
  public Iterator<T> track(Iterator<T> source) {
    Spliterator<T> chunks = Spliterators.spliteratorUnknownSize(source, Spliterator.ORDERED);
    return new Iterator<T>() {
      private boolean ready = false;
      private T next;

      @Override
      public boolean hasNext() {
        if (!ready) {
          ready = advance(chunks, chunk -> next = chunk);
        }
        return ready;
      }

      @Override
      public T next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        ready = false;
        return next;
      }
    };
  }

  private boolean advance(Spliterator<T> chunks, Consumer<? super T> action) {
    // An exception from the caller's code isn't a failed call.
    RuntimeException[] callerError = new RuntimeException[1];
    boolean advanced;
    try {
      advanced =
          chunks.tryAdvance(
              chunk -> {
                onChunk(chunk);
                try {
                  action.accept(chunk);
                } catch (RuntimeException e) {
                  callerError[0] = e;
                  throw e;
                }
              });
    } catch (RuntimeException e) {
      if (e != callerError[0]) {
        fail(e);
      }
      throw e;
    }
    if (!advanced) {
      complete();
    }
    return advanced;
  }

  /** Records a chunk delivered some other way, such as to an async subscriber. */
  public synchronized void onChunk(T chunk) {
    if (chunks.isEmpty()) {
      span.recordTimeToFirstToken();
    }
    chunks.add(chunk);
  }

  /** Marks the stream complete: the span is marked OK, gets the response attributes and ends. */
  public synchronized void complete() {
    if (finished) {
      return;
    }
    finished = true;
    List<T> all = Collections.unmodifiableList(new ArrayList<T>(chunks));
    span.setAttributesSafely(() -> onComplete.apply(all));
    span.getTelemetry().setStatus(StatusCode.OK);
    span.end();
  }

  /** Marks the stream failed: the span records the error, is marked ERROR and ends. */
  public synchronized void fail(Throwable error) {
    if (finished) {
      return;
    }
    finished = true;
    span.fail(error);
    span.end();
  }

  /** Ends the span, without a response, unless the stream has already completed or failed. */
  public synchronized void close() {
    finished = true;
    span.end();
  }
}
