package com.statsig.ai.instrumentation.anthropic;

import com.anthropic.core.http.AsyncStreamResponse;
import com.anthropic.core.http.StreamResponse;
import com.statsig.ai.instrumentation.GenAISpan;
import com.statsig.ai.instrumentation.GenAIStreamTracker;
import com.statsig.ai.instrumentation.GenAITelemetry;
import com.statsig.ai.instrumentation.openai.GenAICaptureOptions;
import com.statsig.ai.instrumentation.openai.ReflectionUtils;
import io.opentelemetry.context.Scope;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Wraps an Anthropic client, or its {@code messages()} service, so {@code messages().create} and
 * {@code createStreaming} calls are recorded as model call spans, like Node's {@code
 * wrapAnthropic}. It works for the blocking and the async client, and passes every other call
 * through.
 */
final class AnthropicServiceHandler implements InvocationHandler {
  private static final String MESSAGES = "messages";

  private final Object delegate;
  // The service's path from the client: empty for the client, or "messages".
  private final String path;
  private final GenAITelemetry telemetry;

  private AnthropicServiceHandler(Object delegate, String path, GenAITelemetry telemetry) {
    this.delegate = delegate;
    this.path = path;
    this.telemetry = telemetry;
  }

  static <T> T wrap(Class<T> type, T delegate, String path, GenAITelemetry telemetry) {
    if (delegate == null) {
      return null;
    }
    return type.cast(
        Proxy.newProxyInstance(
            delegate.getClass().getClassLoader(),
            new Class<?>[] {type},
            new AnthropicServiceHandler(delegate, path, telemetry)));
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    if (method.getDeclaringClass() == Object.class) {
      return ReflectionUtils.delegateInvoke(method, delegate, args);
    }
    String name = method.getName();
    boolean noArgs = args == null || args.length == 0;

    if (path.isEmpty() && noArgs && ("async".equals(name) || "sync".equals(name))) {
      return wrapResult(method, args, "");
    }
    if ("withOptions".equals(name) && method.getReturnType().isInterface()) {
      return wrapResult(method, args, path);
    }
    if (path.isEmpty() && noArgs && MESSAGES.equals(name)) {
      return wrapResult(method, args, MESSAGES);
    }
    boolean traced =
        MESSAGES.equals(path) && ("create".equals(name) || "createStreaming".equals(name));
    if (!traced || noArgs || args[0] == null) {
      return ReflectionUtils.delegateInvoke(method, delegate, args);
    }
    return trace(method, args);
  }

  private Object wrapResult(Method method, Object[] args, String resultPath) throws Throwable {
    Object result = ReflectionUtils.delegateInvoke(method, delegate, args);
    @SuppressWarnings("unchecked")
    Class<Object> type = (Class<Object>) method.getReturnType();
    return wrap(type, result, resultPath, telemetry);
  }

  private Object trace(Method method, Object[] args) throws Throwable {
    GenAICaptureOptions capture = telemetry.getCaptureOptions();
    Map<String, Object> params = AnthropicAttributes.toPlainRequest(args[0]);
    Class<?> returnType = method.getReturnType();
    boolean stream =
        StreamResponse.class.isAssignableFrom(returnType)
            || AsyncStreamResponse.class.isAssignableFrom(returnType);
    String spanName = AnthropicAttributes.spanName(params);
    Map<String, Object> attrs =
        AnthropicAttributes.requestAttributes(
            AnthropicAttributes.SOURCE_MESSAGES_CREATE, params, stream, capture);

    if (stream) {
      GenAISpan span = telemetry.startSpan(spanName, attrs);
      Object result;
      try (Scope ignored = span.getContext().makeCurrent()) {
        result = ReflectionUtils.delegateInvoke(method, delegate, args);
      } catch (Throwable t) {
        span.fail(t);
        span.end();
        throw t;
      }
      GenAIStreamTracker<Object> tracker =
          new GenAIStreamTracker<Object>(span, events -> streamAttributes(events, capture));
      if (result instanceof StreamResponse) {
        return new TracedStream(asStream(result), tracker);
      }
      if (result instanceof AsyncStreamResponse) {
        return new TracedAsyncStream(asAsyncStream(result), tracker);
      }
      span.end();
      return result;
    }

    if (CompletableFuture.class.isAssignableFrom(returnType)) {
      return telemetry.traceAsync(
          spanName,
          attrs,
          () -> asFuture(ReflectionUtils.delegateInvoke(method, delegate, args)),
          message ->
              AnthropicAttributes.responseAttributes(
                  AnthropicAttributes.toPlainResponse(message), capture));
    }
    return telemetry.trace(
        spanName,
        attrs,
        () -> ReflectionUtils.delegateInvoke(method, delegate, args),
        message ->
            AnthropicAttributes.responseAttributes(
                AnthropicAttributes.toPlainResponse(message), capture));
  }

  private static Map<String, Object> streamAttributes(
      List<Object> events, GenAICaptureOptions capture) {
    List<Map<String, Object>> plain = new ArrayList<Map<String, Object>>();
    for (Object event : events) {
      plain.add(AnthropicAttributes.toPlainMap(event));
    }
    return AnthropicAttributes.responseAttributes(
        AnthropicAttributes.aggregateStreamEvents(plain), capture);
  }

  @SuppressWarnings("unchecked")
  private static StreamResponse<Object> asStream(Object stream) {
    return (StreamResponse<Object>) stream;
  }

  @SuppressWarnings("unchecked")
  private static AsyncStreamResponse<Object> asAsyncStream(Object stream) {
    return (AsyncStreamResponse<Object>) stream;
  }

  @SuppressWarnings("unchecked")
  private static CompletableFuture<Object> asFuture(Object future) {
    return (CompletableFuture<Object>) future;
  }

  /** A blocking stream of message events, tracked in its span. */
  private static final class TracedStream implements StreamResponse<Object> {
    private final StreamResponse<Object> delegate;
    private final GenAIStreamTracker<Object> tracker;

    TracedStream(StreamResponse<Object> delegate, GenAIStreamTracker<Object> tracker) {
      this.delegate = delegate;
      this.tracker = tracker;
    }

    @Override
    public java.util.stream.Stream<Object> stream() {
      return tracker.track(delegate.stream());
    }

    @Override
    public void close() {
      delegate.close();
      tracker.close();
    }
  }

  /**
   * An async stream of message events, tracked in its span. Without a subscriber, the span ends
   * when {@link #onCompleteFuture()} completes.
   */
  private static final class TracedAsyncStream implements AsyncStreamResponse<Object> {
    private final AsyncStreamResponse<Object> delegate;
    private final GenAIStreamTracker<Object> tracker;
    private volatile boolean subscribed = false;

    TracedAsyncStream(AsyncStreamResponse<Object> delegate, GenAIStreamTracker<Object> tracker) {
      this.delegate = delegate;
      this.tracker = tracker;
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
    public AsyncStreamResponse<Object> subscribe(Handler<? super Object> handler) {
      subscribed = true;
      delegate.subscribe(tracked(handler));
      return this;
    }

    @Override
    public AsyncStreamResponse<Object> subscribe(
        Handler<? super Object> handler, java.util.concurrent.Executor executor) {
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

    private Handler<Object> tracked(Handler<? super Object> handler) {
      return new Handler<Object>() {
        @Override
        public void onNext(Object event) {
          tracker.onChunk(event);
          handler.onNext(event);
        }

        @Override
        public void onComplete(java.util.Optional<Throwable> error) {
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
}
