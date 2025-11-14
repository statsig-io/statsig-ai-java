package com.statsig.ai.instrumentation.openai;

import com.openai.client.OpenAIClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import com.statsig.ai.trace.StatsigContextUtils;
import com.statsig.ai.trace.StatsigTraceConventions;
import com.statsig.ai.trace.UserContext;
import com.statsig.ai.trace.singleton.OtelSingleton;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Wraps an {@link OpenAIClient} and adds lightweight OpenTelemetry spans for common operations. */
public final class OpenAIWrapper {
  private OpenAIWrapper() {}

  public static OpenAIClient wrap(OpenAIClient client) {
    return wrap(client, Collections.emptyMap());
  }

  public static OpenAIClient wrap(OpenAIClient client, Map<String, String> defaultAttributes) {
    Objects.requireNonNull(client, "client");

    Map<String, String> attributes =
        defaultAttributes == null ? Collections.emptyMap() : defaultAttributes;
    return InstrumentedOpenAIClient.wrap(client, attributes);
  }

  private static final class InstrumentedOpenAIClient implements InvocationHandler {
    private final OpenAIClient delegate;
    private final OpenAITelemetry telemetry;

    private InstrumentedOpenAIClient(OpenAIClient delegate, Map<String, String> defaultAttributes) {
      this.delegate = delegate;
      this.telemetry = new OpenAITelemetry(defaultAttributes);
    }

    static OpenAIClient wrap(OpenAIClient delegate, Map<String, String> defaultAttributes) {
      return (OpenAIClient)
          Proxy.newProxyInstance(
              delegate.getClass().getClassLoader(),
              new Class<?>[] {OpenAIClient.class},
              new InstrumentedOpenAIClient(delegate, defaultAttributes));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if (method.getDeclaringClass() == Object.class) {
        return method.invoke(delegate, args);
      }

      if ("chat".equals(method.getName()) && (args == null || args.length == 0)) {
        ChatService chatService = (ChatService) method.invoke(delegate, args);
        return InstrumentedChatService.wrap(chatService, telemetry);
      }

      return method.invoke(delegate, args);
    }
  }

  private static final class InstrumentedChatService implements InvocationHandler {
    private final ChatService delegate;
    private final OpenAITelemetry telemetry;

    private InstrumentedChatService(ChatService delegate, OpenAITelemetry telemetry) {
      this.delegate = delegate;
      this.telemetry = telemetry;
    }

    static ChatService wrap(ChatService delegate, OpenAITelemetry telemetry) {
      if (delegate == null) {
        return null;
      }
      return (ChatService)
          Proxy.newProxyInstance(
              delegate.getClass().getClassLoader(),
              new Class<?>[] {ChatService.class},
              new InstrumentedChatService(delegate, telemetry));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if (method.getDeclaringClass() == Object.class) {
        return method.invoke(delegate, args);
      }

      if ("completions".equals(method.getName()) && (args == null || args.length == 0)) {
        ChatCompletionService completionService =
            (ChatCompletionService) method.invoke(delegate, args);
        return InstrumentedChatCompletionService.wrap(completionService, telemetry);
      }

      return method.invoke(delegate, args);
    }
  }

  private static final class InstrumentedChatCompletionService implements InvocationHandler {
    private final ChatCompletionService delegate;
    private final OpenAITelemetry telemetry;

    private InstrumentedChatCompletionService(
        ChatCompletionService delegate, OpenAITelemetry telemetry) {
      this.delegate = delegate;
      this.telemetry = telemetry;
    }

    static ChatCompletionService wrap(
        ChatCompletionService delegate, OpenAITelemetry telemetry) {
      if (delegate == null) {
        return null;
      }
      return (ChatCompletionService)
          Proxy.newProxyInstance(
              delegate.getClass().getClassLoader(),
              new Class<?>[] {ChatCompletionService.class},
              new InstrumentedChatCompletionService(delegate, telemetry));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if (method.getDeclaringClass() == Object.class) {
        return method.invoke(delegate, args);
      }

      if ("create".equals(method.getName())
          && args != null
          && args.length > 0
          && args[0] instanceof ChatCompletionCreateParams) {
        ChatCompletionCreateParams params = (ChatCompletionCreateParams) args[0];
        return telemetry.trace(
            "openai.chat.completions",
            StatsigTraceConventions.GEN_AI_SPAN_TYPE_CHAT,
            span -> {
              OpenAIAttributes.applyRequestAttributes(span, "chat.completions", params);
              Object response = invokeDelegate(method, delegate, args);
              OpenAIAttributes.applyResponseAttributes(span, response);
              OpenAIAttributes.applyUsageAttributes(span, response);
              return response;
            });
      }

      return method.invoke(delegate, args);
    }
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

    <T> T trace(String spanName, String genAISpanType, SpanCallable<T> callable) throws Throwable {
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
        T result = callable.call(span);
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
      if (defaultAttributes.isEmpty()) {
        return;
      }
      for (Map.Entry<String, String> entry : defaultAttributes.entrySet()) {
        if (entry.getKey() != null && entry.getValue() != null) {
          span.setAttribute(entry.getKey(), entry.getValue());
        }
      }
    }

    @FunctionalInterface
    private interface SpanCallable<T> {
      T call(Span span) throws Throwable;
    }
  }

  private static final class OpenAIAttributes {
    private OpenAIAttributes() {}

    static void applyRequestAttributes(
        Span span, String operationName, ChatCompletionCreateParams params) {
      if (span == null || params == null) {
        return;
      }

      span.setAttribute("gen_ai.operation.name", operationName);
      setAttribute(span, "gen_ai.request.model", readString(params, "model"));

      Number maxTokens =
          readNumber(params, "maxTokens", "max_tokens", "maxCompletionTokens", "max_completion_tokens");
      if (maxTokens != null) {
        setAttribute(span, "gen_ai.request.max_tokens", maxTokens);
      }

      setAttribute(
          span, "gen_ai.request.temperature", readNumber(params, "temperature", "temperatureValue"));
      setAttribute(span, "gen_ai.request.top_p", readNumber(params, "topP", "top_p"));
      setAttribute(span, "gen_ai.request.top_k", readNumber(params, "topK", "top_k"));
      setAttribute(
          span,
          "gen_ai.request.frequency_penalty",
          readNumber(params, "frequencyPenalty", "frequency_penalty"));
      setAttribute(
          span,
          "gen_ai.request.presence_penalty",
          readNumber(params, "presencePenalty", "presence_penalty"));

      Object stopSequences = readValue(params, "stop", "stopSequences", "stop_sequences");
      setCollectionAttribute(span, "gen_ai.request.stop_sequences", stopSequences);

      Number choiceCount = readNumber(params, "n");
      if (choiceCount != null && choiceCount.longValue() != 1L) {
        setAttribute(span, "gen_ai.request.choice.count", choiceCount);
      }

      setAttribute(span, "gen_ai.request.seed", readNumber(params, "seed"));
      setAttribute(span, "gen_ai.conversation.id", readString(params, "conversationId", "conversation_id"));

      Object responseFormat = readValue(params, "responseFormat", "response_format");
      if (responseFormat != null) {
        setAttribute(span, "gen_ai.output.type", readString(responseFormat, "type"));
      }

      Object tools = readValue(params, "tools");
      setCollectionAttribute(span, "gen_ai.tool.definitions", tools);

      Object messages = readValue(params, "messages");
      setCollectionAttribute(span, "gen_ai.input.messages", messages);

      Object systemMessages = filterMessagesByRole(messages, "system");
      if (systemMessages != null) {
        setCollectionAttribute(span, "gen_ai.system.instructions", systemMessages);
      }
    }

    static void applyResponseAttributes(Span span, Object response) {
      if (span == null || response == null) {
        return;
      }

      setAttribute(span, "gen_ai.response.id", readString(response, "id"));
      setAttribute(span, "gen_ai.response.model", readString(response, "model"));

      List<?> choices = readList(response, "choices");
      if (choices != null) {
        List<String> finishReasons = new ArrayList<>();
        for (Object choice : choices) {
          Object finish = readValue(choice, "finishReason", "finish_reason");
          if (finish != null) {
            finishReasons.add(finish.toString());
          }
        }
        if (!finishReasons.isEmpty()) {
          span.setAttribute(
              AttributeKey.stringArrayKey("gen_ai.response.finish_reasons"), finishReasons);
        }

        setCollectionAttribute(span, "gen_ai.output.messages", choices);
      }
    }

    static void applyUsageAttributes(Span span, Object response) {
      if (span == null || response == null) {
        return;
      }

      Object usage = readValue(response, "usage");
      if (usage == null) {
        return;
      }

      setAttribute(
          span,
          "gen_ai.usage.input_tokens",
          readNumber(usage, "promptTokens", "prompt_tokens", "inputTokens", "input_tokens"));
      setAttribute(
          span,
          "gen_ai.usage.output_tokens",
          readNumber(
              usage, "completionTokens", "completion_tokens", "outputTokens", "output_tokens"));
    }

    private static List<String> filterMessagesByRole(Object messages, String role) {
      if (role == null || !(messages instanceof List<?>)) {
        return null;
      }
      List<?> list = (List<?>) messages;
      List<String> filtered = new ArrayList<>();
      for (Object message : list) {
        String messageRole = readString(message, "role");
        if (role.equals(messageRole)) {
          filtered.add(message.toString());
        }
      }
      return filtered.isEmpty() ? null : filtered;
    }

    private static void setCollectionAttribute(Span span, String key, Object value) {
      if (span == null || key == null || value == null) {
        return;
      }

      if (value instanceof List<?>) {
        setListAttribute(span, key, (List<?>) value);
        return;
      }

      if (value.getClass().isArray()) {
        int length = Array.getLength(value);
        List<Object> values = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
          values.add(Array.get(value, i));
        }
        setListAttribute(span, key, values);
        return;
      }

      span.setAttribute(key, value.toString());
    }

    private static void setListAttribute(Span span, String key, List<?> values) {
      if (span == null || key == null || values == null || values.isEmpty()) {
        return;
      }

      List<String> asStrings = new ArrayList<>();
      for (Object value : values) {
        if (value != null) {
          asStrings.add(value.toString());
        }
      }

      if (!asStrings.isEmpty()) {
        span.setAttribute(AttributeKey.stringArrayKey(key), asStrings);
      }
    }

    private static void setAttribute(Span span, String key, Object value) {
      if (span == null || key == null || value == null) {
        return;
      }

      if (value instanceof String) {
        span.setAttribute(key, (String) value);
        return;
      }

      if (value instanceof Number) {
        Number numberValue = (Number) value;
        if (numberValue instanceof Double || numberValue instanceof Float) {
          span.setAttribute(key, numberValue.doubleValue());
        } else {
          span.setAttribute(key, numberValue.longValue());
        }
        return;
      }

      if (value instanceof Boolean) {
        span.setAttribute(key, (Boolean) value);
        return;
      }

      if (value instanceof List<?>) {
        setListAttribute(span, key, (List<?>) value);
        return;
      }

      span.setAttribute(key, value.toString());
    }

    private static String readString(Object target, String... methodNames) {
      Object value = readValue(target, methodNames);
      return value == null ? null : value.toString();
    }

    private static Number readNumber(Object target, String... methodNames) {
      Object value = readValue(target, methodNames);
      if (value instanceof Number) {
        return (Number) value;
      }
      if (value instanceof String) {
        String stringValue = (String) value;
        try {
          if (stringValue.contains(".")) {
            return Double.parseDouble(stringValue);
          }
          return Long.parseLong(stringValue);
        } catch (NumberFormatException ignored) {
          return null;
        }
      }
      return null;
    }

    private static List<?> readList(Object target, String... methodNames) {
      Object value = readValue(target, methodNames);
      return (value instanceof List<?>) ? (List<?>) value : null;
    }

    private static Object readValue(Object target, String... methodNames) {
      if (target == null || methodNames == null) {
        return null;
      }

      for (String methodName : methodNames) {
        if (methodName == null) {
          continue;
        }
        try {
          Method method = target.getClass().getMethod(methodName);
          Object value = method.invoke(target);
          return unwrapOptional(value);
        } catch (NoSuchMethodException ignored) {
          // move on to the next candidate
        } catch (IllegalAccessException | InvocationTargetException ignored) {
          return null;
        }
      }
      return null;
    }

    private static Object unwrapOptional(Object value) {
      if (value instanceof Optional<?>) {
        Optional<?> optional = (Optional<?>) value;
        return optional.orElse(null);
      }
      return value;
    }
  }
}
