package com.statsig.ai.instrumentation.openai;

import com.openai.client.OpenAIClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import com.statsig.ai.trace.StatsigContextUtils;
import com.statsig.ai.trace.StatsigTraceConventions;
import com.statsig.ai.trace.UserContext;
import com.statsig.ai.trace.singleton.OtelSingleton;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Wraps an {@link OpenAIClient} and adds lightweight OpenTelemetry spans following Statsig's
 * GenAI conventions. MVP implementation focuses solely on chat completions so we can iterate on
 * telemetry behavior.
 */
public final class OpenAIWrapper {
  private OpenAIWrapper() {}

  public static OpenAIClient wrap(OpenAIClient client) {
    return wrap(client, OpenAIWrapperConfig.builder().build());
  }

  public static OpenAIClient wrap(OpenAIClient client, OpenAIWrapperConfig config) {
    Objects.requireNonNull(client, "client");
    Objects.requireNonNull(config, "config");

    return InstrumentedOpenAIClient.wrap(client, config);
  }

  private static final class InstrumentedOpenAIClient implements InvocationHandler {
    private final OpenAIClient delegate;
    private final OpenAITelemetry telemetry;

    private InstrumentedOpenAIClient(OpenAIClient delegate, OpenAIWrapperConfig config) {
      this.delegate = delegate;
      this.telemetry = new OpenAITelemetry(config.getDefaultAttributes());
    }

    static OpenAIClient wrap(OpenAIClient delegate, OpenAIWrapperConfig config) {
      return (OpenAIClient)
          Proxy.newProxyInstance(
              delegate.getClass().getClassLoader(),
              new Class<?>[] {OpenAIClient.class},
              new InstrumentedOpenAIClient(delegate, config));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if (method.getDeclaringClass() == Object.class) {
        return method.invoke(delegate, args);
      }

      String name = method.getName();
      if ("chat".equals(name) && (args == null || args.length == 0)) {
        ChatService chatService = (ChatService) method.invoke(delegate, args);
        return instrumentChatService(chatService, telemetry);
      }

      return method.invoke(delegate, args);
    }
  }

  private static ChatService instrumentChatService(
      ChatService delegate, OpenAITelemetry telemetry) {
    if (delegate == null) {
      return null;
    }
    return wrapService(
        ChatService.class,
        delegate,
        telemetry,
        ServiceOperation.forwarding(
            (method, args) -> "completions".equals(method.getName()) && method.getParameterCount() == 0,
            (target, method, args, tel) ->
                instrumentChatCompletionService(
                    (ChatCompletionService) invokeDelegate(method, target, args), tel)));
  }

  private static ChatCompletionService instrumentChatCompletionService(
      ChatCompletionService delegate, OpenAITelemetry telemetry) {
    if (delegate == null) {
      return null;
    }
    return wrapService(
        ChatCompletionService.class,
        delegate,
        telemetry,
        ServiceOperation.tracing(
            "openai.chat.completions",
            StatsigTraceConventions.GEN_AI_SPAN_TYPE_CHAT,
            (method, args) ->
                "create".equals(method.getName())
                    && args != null
                    && args.length > 0
                    && args[0] instanceof ChatCompletionCreateParams));
  }

  private static <T> T wrapService(
      Class<T> serviceInterface,
      T delegate,
      OpenAITelemetry telemetry,
      ServiceOperation... operations) {
    InvocationHandler handler =
        (proxy, method, args) -> {
          if (method.getDeclaringClass() == Object.class) {
            return method.invoke(delegate, args);
          }
          for (ServiceOperation operation : operations) {
            if (operation.matches(method, args)) {
              return operation.handle(delegate, method, args, telemetry);
            }
          }
          return method.invoke(delegate, args);
        };

    return serviceInterface.cast(
        Proxy.newProxyInstance(
            delegate.getClass().getClassLoader(),
            new Class<?>[] {serviceInterface},
            handler));
  }

  private interface ServiceOperation {
    boolean matches(Method method, Object[] args);

    Object handle(Object target, Method method, Object[] args, OpenAITelemetry telemetry)
        throws Throwable;

    static ServiceOperation tracing(
        String spanName, String spanType, MethodPredicate predicate) {
      return new ServiceOperation() {
        @Override
        public boolean matches(Method method, Object[] args) {
          return predicate.test(method, args);
        }

        @Override
        public Object handle(
            Object target, Method method, Object[] args, OpenAITelemetry telemetry)
            throws Throwable {
          return telemetry.trace(
              spanName, spanType, () -> invokeDelegate(method, target, args));
        }
      };
    }

    static ServiceOperation forwarding(
        MethodPredicate predicate, ServiceWrapper wrapper) {
      return new ServiceOperation() {
        @Override
        public boolean matches(Method method, Object[] args) {
          return predicate.test(method, args);
        }

        @Override
        public Object handle(
            Object target, Method method, Object[] args, OpenAITelemetry telemetry)
            throws Throwable {
          return wrapper.wrap(target, method, args, telemetry);
        }
      };
    }
  }

  @FunctionalInterface
  private interface MethodPredicate {
    boolean test(Method method, Object[] args);
  }

  @FunctionalInterface
  private interface ServiceWrapper {
    Object wrap(Object target, Method method, Object[] args, OpenAITelemetry telemetry)
        throws Throwable;
  }

  private static Object invokeDelegate(Method method, Object target, Object[] args)
      throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getTargetException();
    }
  }

  private static final class OpenAITelemetry {
    private final Tracer tracer =
        OtelSingleton.getTracerProviderStatic().get("com.statsig.ai.instrumentation.openai");
    private final Map<String, String> defaultAttributes;

    private OpenAITelemetry(Map<String, String> defaultAttributes) {
      this.defaultAttributes =
          defaultAttributes == null
              ? Collections.emptyMap()
              : Collections.unmodifiableMap(new HashMap<>(defaultAttributes));
    }

    Object trace(String spanName, String genAISpanType, ThrowingCallable callable)
        throws Throwable {
      Context parentCtx = Context.current();
      boolean rootMissing = parentCtx.get(StatsigTraceConventions.CTX_GEN_AI_ROOT) == null;
      Context spanParent =
          rootMissing
              ? parentCtx.with(StatsigTraceConventions.CTX_GEN_AI_ROOT, spanName)
              : parentCtx;

      Span span =
          tracer
              .spanBuilder(spanName)
              .setSpanKind(SpanKind.CLIENT)
              .setParent(spanParent)
              .startSpan();

      span.setAttribute(
          StatsigTraceConventions.ATTR_SPAN_TYPE, StatsigTraceConventions.SPAN_TYPE_GEN_AI);
      span.setAttribute(StatsigTraceConventions.ATTR_GEN_AI_SPAN_TYPE, genAISpanType);
      span.setAttribute("gen_ai.operation.name", spanName);
      if (rootMissing) {
        span.setAttribute(
            StatsigTraceConventions.ATTR_SPAN_LLM_ROOT,
            StatsigTraceConventions.SPAN_LLM_ROOT_VALUE);
      }
      applyContextAttributes(span, spanParent);
      applyDefaultAttributes(span);

      Context spanContext = span.storeInContext(spanParent);

      try (Scope scope = spanContext.makeCurrent()) {
        Object result = callable.call();
        span.setStatus(StatusCode.OK);
        return result;
      } catch (Throwable t) {
        span.recordException(t);
        span.setStatus(StatusCode.ERROR, t.getMessage());
        throw t;
      } finally {
        span.end();
      }
    }

    private void applyContextAttributes(Span span, Context ctx) {
      String promptName = ctx.get(StatsigTraceConventions.CTX_ACTIVE_PROMPT);
      if (promptName != null) {
        span.setAttribute(StatsigTraceConventions.ATTR_LLM_PROMPT_NAME, promptName);
      }
      String promptVersion = ctx.get(StatsigTraceConventions.CTX_ACTIVE_PROMPT_VERSION);
      if (promptVersion != null) {
        span.setAttribute(StatsigTraceConventions.ATTR_LLM_PROMPT_VERSION, promptVersion);
      }

      UserContext userContext = StatsigContextUtils.getActiveUser(ctx);
      if (userContext != null) {
        span.setAttribute(StatsigTraceConventions.ATTR_USER_ID, userContext.getUserID());
        if (!userContext.getCustomIDs().isEmpty()) {
          span.setAttribute(
              StatsigTraceConventions.ATTR_CUSTOM_IDS, userContext.getCustomIDs().toString());
        }
      }
    }

    private void applyDefaultAttributes(Span span) {
      if (defaultAttributes == null || defaultAttributes.isEmpty()) {
        return;
      }
      for (Map.Entry<String, String> entry : defaultAttributes.entrySet()) {
        if (entry.getKey() != null && entry.getValue() != null) {
          span.setAttribute(entry.getKey(), entry.getValue());
        }
      }
    }

    @FunctionalInterface
    private interface ThrowingCallable {
      Object call() throws Throwable;
    }
  }
}
