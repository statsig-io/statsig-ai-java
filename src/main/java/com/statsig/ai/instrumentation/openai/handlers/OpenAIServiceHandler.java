package com.statsig.ai.instrumentation.openai.handlers;

import com.openai.core.http.AsyncStreamResponse;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.StructuredResponseCreateParams;
import com.statsig.ai.instrumentation.GenAISpan;
import com.statsig.ai.instrumentation.GenAITelemetry;
import com.statsig.ai.instrumentation.openai.GenAICaptureOptions;
import com.statsig.ai.instrumentation.openai.ReflectionUtils;
import com.statsig.ai.instrumentation.openai.telemetry.OpenAIAttributes;
import io.opentelemetry.context.Scope;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Wraps an openai-java client, or one of its services, so the calls the Node SDK traces are
 * recorded as model call spans, like Node's {@code StatsigOpenAIProxy}. It works for the blocking
 * and the async client: services are wrapped as they're reached, such as {@code
 * client.chat().completions()}, and every other method is passed through.
 *
 * <p>Traced calls, by service path and method:
 *
 * <ul>
 *   <li>{@code chat().completions().create} and {@code createStreaming}
 *   <li>{@code completions().create} and {@code createStreaming}
 *   <li>{@code embeddings().create}
 *   <li>{@code images().generate}
 *   <li>{@code responses().create} and {@code createStreaming}. Structured-output params are traced
 *       as {@code openai.responses.parse}, like Node's {@code responses.parse}.
 * </ul>
 *
 * <p>A blocking call's span ends when it returns or throws. An async call's span ends when its
 * future completes, and the returned future completes after that. A stream's span ends when the
 * stream has been read to the end, fails, or is closed.
 */
public final class OpenAIServiceHandler implements InvocationHandler {

  // Methods that return a service to wrap, by path from the client.
  private static final Set<String> SERVICES =
      new HashSet<String>(
          Arrays.asList(
              "chat", "chat.completions", "completions", "embeddings", "images", "responses"));

  // Traced methods, by path from the client, and their Node source names.
  private static final Map<String, String> SOURCE_NAMES;

  static {
    Map<String, String> names = new HashMap<String, String>();
    names.put("chat.completions.create", "openai.chat.completions.create");
    names.put("chat.completions.createStreaming", "openai.chat.completions.create");
    names.put("completions.create", "openai.completions.create");
    names.put("completions.createStreaming", "openai.completions.create");
    names.put("embeddings.create", "openai.embeddings.create");
    names.put("images.generate", "openai.images.generate");
    names.put("responses.create", "openai.responses.create");
    names.put("responses.createStreaming", "openai.responses.create");
    SOURCE_NAMES = Collections.unmodifiableMap(names);
  }

  private static final String RESPONSES_PARSE = "openai.responses.parse";

  private final Object delegate;
  // The service's path from the client, such as "chat.completions"; empty for the client.
  private final String path;
  private final GenAITelemetry telemetry;

  private OpenAIServiceHandler(Object delegate, String path, GenAITelemetry telemetry) {
    this.delegate = delegate;
    this.path = path;
    this.telemetry = telemetry;
  }

  /**
   * Returns a proxy of {@code type} that traces {@code delegate}'s calls. Returns null when {@code
   * delegate} is null.
   */
  public static <T> T wrap(Class<T> type, T delegate, String path, GenAITelemetry telemetry) {
    if (delegate == null) {
      return null;
    }
    return type.cast(
        Proxy.newProxyInstance(
            delegate.getClass().getClassLoader(),
            new Class<?>[] {type},
            new OpenAIServiceHandler(delegate, path, telemetry)));
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    if (method.getDeclaringClass() == Object.class) {
      return ReflectionUtils.delegateInvoke(method, delegate, args);
    }

    String name = method.getName();
    boolean noArgs = args == null || args.length == 0;
    String childPath = path.isEmpty() ? name : path + "." + name;

    // async() and sync() switch between the blocking and the async client.
    if (path.isEmpty() && noArgs && ("async".equals(name) || "sync".equals(name))) {
      return wrapResult(method, args, "");
    }
    // withOptions returns the same service with other request options.
    if ("withOptions".equals(name) && method.getReturnType().isInterface()) {
      return wrapResult(method, args, path);
    }
    if (noArgs && SERVICES.contains(childPath)) {
      return wrapResult(method, args, childPath);
    }

    String sourceName = SOURCE_NAMES.get(childPath);
    if (sourceName == null || noArgs || args[0] == null) {
      return ReflectionUtils.delegateInvoke(method, delegate, args);
    }
    if (args[0] instanceof StructuredResponseCreateParams) {
      sourceName = RESPONSES_PARSE;
    }
    return trace(method, args, sourceName);
  }

  private Object wrapResult(Method method, Object[] args, String resultPath) throws Throwable {
    Object result = ReflectionUtils.delegateInvoke(method, delegate, args);
    @SuppressWarnings("unchecked")
    Class<Object> type = (Class<Object>) method.getReturnType();
    return wrap(type, result, resultPath, telemetry);
  }

  private Object trace(Method method, Object[] args, String sourceName) throws Throwable {
    GenAICaptureOptions capture = telemetry.getCaptureOptions();
    Map<String, Object> params = OpenAIAttributes.toPlainRequest(args[0]);
    String spanName = OpenAIAttributes.spanName(sourceName, params);
    Map<String, Object> attrs = OpenAIAttributes.requestAttributes(sourceName, params, capture);
    Class<?> returnType = method.getReturnType();

    if (StreamResponse.class.isAssignableFrom(returnType)
        || AsyncStreamResponse.class.isAssignableFrom(returnType)) {
      attrs.put("gen_ai.request.stream", true);
      GenAISpan span = telemetry.startSpan(spanName, attrs);
      Object stream;
      try (Scope ignored = span.getContext().makeCurrent()) {
        stream = ReflectionUtils.delegateInvoke(method, delegate, args);
      } catch (Throwable t) {
        span.fail(t);
        span.end();
        throw t;
      }
      if (stream instanceof StreamResponse) {
        return new TracedStreamResponse<Object>(asStream(stream), span, capture);
      }
      if (stream instanceof AsyncStreamResponse) {
        return new TracedAsyncStreamResponse<Object>(asAsyncStream(stream), span, capture);
      }
      span.end();
      return stream;
    }

    if (CompletableFuture.class.isAssignableFrom(returnType)) {
      return telemetry.traceAsync(
          spanName,
          attrs,
          () -> asFuture(ReflectionUtils.delegateInvoke(method, delegate, args)),
          response ->
              OpenAIAttributes.responseAttributes(
                  OpenAIAttributes.toPlainResponse(response), capture));
    }

    return telemetry.trace(
        spanName,
        attrs,
        () -> ReflectionUtils.delegateInvoke(method, delegate, args),
        response ->
            OpenAIAttributes.responseAttributes(
                OpenAIAttributes.toPlainResponse(response), capture));
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
}
