package com.statsig.ai.instrumentation.openai.handlers;

import com.openai.core.http.StreamResponse;
import com.statsig.ai.instrumentation.GenAISpan;
import com.statsig.ai.instrumentation.openai.GenAICaptureOptions;
import com.statsig.ai.instrumentation.openai.telemetry.OpenAIAttributes;
import io.opentelemetry.api.trace.StatusCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A blocking stream whose model call span records the time to the first chunk, and ends when the
 * stream has been read to the end (marked OK, with the response rebuilt from the chunks), when
 * reading it fails (marked ERROR), or when it's closed first, like Node's {@code TelemetryStream}.
 */
final class TracedStreamResponse<T> implements StreamResponse<T> {
  private final StreamResponse<T> delegate;
  private final GenAISpan span;
  private final GenAICaptureOptions capture;
  private final List<T> chunks = new ArrayList<T>();
  private boolean finished = false;

  TracedStreamResponse(StreamResponse<T> delegate, GenAISpan span, GenAICaptureOptions capture) {
    this.delegate = delegate;
    this.span = span;
    this.capture = capture;
  }

  @Override
  public Stream<T> stream() {
    Spliterator<T> source = delegate.stream().spliterator();
    Spliterator<T> traced =
        new Spliterators.AbstractSpliterator<T>(Long.MAX_VALUE, Spliterator.ORDERED) {
          @Override
          public boolean tryAdvance(Consumer<? super T> action) {
            // An exception from the caller's code isn't a failed call.
            RuntimeException[] callerError = new RuntimeException[1];
            boolean advanced;
            try {
              advanced =
                  source.tryAdvance(
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
              succeed();
            }
            return advanced;
          }
        };
    return StreamSupport.stream(traced, false).onClose(this::close);
  }

  @Override
  public void close() {
    delegate.close();
    end();
  }

  private synchronized void onChunk(T chunk) {
    if (chunks.isEmpty()) {
      span.recordTimeToFirstToken();
    }
    chunks.add(chunk);
  }

  private synchronized void succeed() {
    if (finished) {
      return;
    }
    span.setAttributesSafely(() -> streamAttributes(chunks, capture));
    span.getTelemetry().setStatus(StatusCode.OK);
    end();
  }

  private synchronized void fail(Throwable error) {
    if (finished) {
      return;
    }
    span.fail(error);
    end();
  }

  private synchronized void end() {
    finished = true;
    span.end();
  }

  static Map<String, Object> streamAttributes(List<?> chunks, GenAICaptureOptions capture) {
    List<Map<String, Object>> plain = new ArrayList<Map<String, Object>>();
    for (Object chunk : chunks) {
      plain.add(OpenAIAttributes.toPlainMap(chunk));
    }
    return OpenAIAttributes.streamAttributes(plain, capture);
  }
}
