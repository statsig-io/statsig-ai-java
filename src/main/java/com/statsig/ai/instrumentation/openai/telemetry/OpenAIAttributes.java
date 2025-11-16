package com.statsig.ai.instrumentation.openai.telemetry;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Extracts OpenAI request/response attributes into telemetry.
 *
 * <p>This class has NO state and NO OpenTelemetry references. It purely parses OpenAI SDK models
 * via reflection.
 */
public final class OpenAIAttributes {

  private OpenAIAttributes() {}

  // ======================================================
  // ===============  Public API  =========================
  // ======================================================

  /** Extracts attributes from ChatCompletionCreateParams. */
  public static void applyRequestAttributes(
      SpanTelemetry span, String operationName, Object params) {

    if (span == null || params == null) {
      return;
    }

    span.setAttribute("gen_ai.operation.name", operationName);
    span.setAttribute("gen_ai.request.model", readString(params, "model"));

    // numeric controls
    setNumber(
        span,
        "gen_ai.request.max_tokens",
        readNumber(
            params, "maxTokens", "max_tokens", "maxCompletionTokens", "max_completion_tokens"));

    setNumber(
        span, "gen_ai.request.temperature", readNumber(params, "temperature", "temperatureValue"));

    setNumber(span, "gen_ai.request.top_p", readNumber(params, "topP", "top_p"));

    setNumber(span, "gen_ai.request.top_k", readNumber(params, "topK", "top_k"));

    setNumber(
        span,
        "gen_ai.request.frequency_penalty",
        readNumber(params, "frequencyPenalty", "frequency_penalty"));

    setNumber(
        span,
        "gen_ai.request.presence_penalty",
        readNumber(params, "presencePenalty", "presence_penalty"));

    // n choices
    Number n = readNumber(params, "n");
    if (n != null && n.longValue() != 1L) {
      span.setAttribute("gen_ai.request.choice.count", n.longValue());
    }

    // seed
    setNumber(span, "gen_ai.request.seed", readNumber(params, "seed"));

    // conversation id
    span.setAttribute(
        "gen_ai.conversation.id", readString(params, "conversationId", "conversation_id"));

    // response format
    Object responseFormat = readValue(params, "responseFormat", "response_format");
    if (responseFormat != null) {
      span.setAttribute("gen_ai.output.type", readString(responseFormat, "type"));
    }

    // tools
    Object tools = readValue(params, "tools");
    if (tools != null) {
      span.setJsonAttribute("gen_ai.tool.definitions", tools);
    }

    // messages
    Object messages = readValue(params, "messages");
    if (messages != null) {
      span.setJsonAttribute("gen_ai.input.messages", messages);
    }

    // system instructions (subset)
    Object systemMessages = filterMessagesByRole(messages, "system");
    if (systemMessages != null) {
      span.setJsonAttribute("gen_ai.system.instructions", systemMessages);
    }

    // stop sequences
    Object stop = readValue(params, "stop", "stopSequences", "stop_sequences");
    if (stop != null) {
      span.setAttribute("gen_ai.request.stop_sequences", stop);
    }
  }

  /** Extracts attributes from OpenAI ChatCompletion response. */
  public static void applyResponseAttributes(SpanTelemetry span, Object response) {
    if (span == null || response == null) {
      return;
    }

    span.setAttribute("gen_ai.response.id", readString(response, "id"));

    span.setAttribute("gen_ai.response.model", readString(response, "model"));

    // choices
    List<?> choices = readList(response, "choices");
    if (choices != null) {

      // finish reasons
      List<String> finishReasons = new ArrayList<>();
      for (Object choice : choices) {
        Object finish = readValue(choice, "finishReason", "finish_reason");
        if (finish != null) finishReasons.add(finish.toString());
      }
      if (!finishReasons.isEmpty()) {
        span.setAttribute("gen_ai.response.finish_reasons", finishReasons);
      }

      // output messages
      span.setJsonAttribute("gen_ai.output.messages", choices);
    }
  }

  /** Extracts usage metrics from response. */
  public static void applyUsageAttributes(SpanTelemetry span, Object response) {
    if (span == null || response == null) {
      return;
    }

    Object usage = readValue(response, "usage");
    if (usage == null) {
      return;
    }

    setNumber(
        span,
        "gen_ai.usage.input_tokens",
        readNumber(usage, "promptTokens", "prompt_tokens", "inputTokens", "input_tokens"));

    setNumber(
        span,
        "gen_ai.usage.output_tokens",
        readNumber(
            usage, "completionTokens", "completion_tokens", "outputTokens", "output_tokens"));
  }

  // ======================================================
  // ===============  Helper Utilities  ===================
  // ======================================================

  private static void setNumber(SpanTelemetry span, String key, Number value) {
    if (value != null) {
      span.setAttribute(key, value);
    }
  }

  private static List<String> filterMessagesByRole(Object messages, String role) {
    if (!(messages instanceof List<?>) || role == null) {
      return null;
    }

    List<String> filtered = new ArrayList<>();
    for (Object msg : (List<?>) messages) {
      String r = readString(msg, "role");
      if (role.equals(r)) {
        filtered.add(String.valueOf(msg));
      }
    }
    return filtered.isEmpty() ? null : filtered;
  }

  // reflection helpers
  private static String readString(Object target, String... methods) {
    Object value = readValue(target, methods);
    return value == null ? null : value.toString();
  }

  private static Number readNumber(Object target, String... methods) {
    Object v = readValue(target, methods);
    if (v instanceof Number) {
      return (Number) v;
    }
    if (v instanceof String) {
      try {
        String s = (String) v;
        return s.contains(".") ? Double.parseDouble(s) : Long.parseLong(s);
      } catch (Exception ignore) {
        // ignore
      }
    }
    return null;
  }

  private static List<?> readList(Object target, String... methods) {
    Object v = readValue(target, methods);
    if (v instanceof List<?>) {
      return (List<?>) v;
    }
    return null;
  }

  private static Object readValue(Object target, String... methodNames) {
    if (target == null) {
      return null;
    }

    for (String name : methodNames) {
      if (name == null) {
        continue;
      }
      try {
        Method m = target.getClass().getMethod(name);
        Object value = m.invoke(target);
        if (value instanceof Optional<?>) {
          return ((Optional<?>) value).orElse(null);
        }
        return value;
      } catch (NoSuchMethodException ignore) {
      } catch (Exception ignore) {
        return null;
      }
    }

    return null;
  }
}
