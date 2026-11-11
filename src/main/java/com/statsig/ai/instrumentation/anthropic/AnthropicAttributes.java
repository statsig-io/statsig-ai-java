package com.statsig.ai.instrumentation.anthropic;

import static com.statsig.ai.instrumentation.PlainJson.get;
import static com.statsig.ai.instrumentation.PlainJson.list;
import static com.statsig.ai.instrumentation.PlainJson.map;
import static com.statsig.ai.instrumentation.PlainJson.number;
import static com.statsig.ai.instrumentation.PlainJson.string;

import com.anthropic.core.ObjectMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.statsig.ai.instrumentation.GenAIMessages;
import com.statsig.ai.instrumentation.openai.GenAICaptureOptions;
import com.statsig.ai.trace.StatsigTraceConventions;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads Anthropic span attributes from requests and responses, a port of the attribute code in the
 * Node SDK's {@code anthropic.ts}. SDK objects are first turned into plain JSON in the API's wire
 * format, so they're read with the same keys as in Node.
 */
public final class AnthropicAttributes {
  private AnthropicAttributes() {}

  /** The tracer name for Anthropic model call spans. */
  public static final String INSTRUMENTATION_NAME = "com.statsig.ai.instrumentation.anthropic";

  public static final String PROVIDER_NAME = "anthropic";
  static final String SOURCE_MESSAGES_CREATE = "anthropic.messages.create";

  private static final ObjectMapper PLAIN_MAPPER = new ObjectMapper();

  // ======================================================
  // ===============  Plain JSON  =========================
  // ======================================================

  /**
   * Returns the request body of an Anthropic SDK params object as plain JSON. Structured-output
   * params are read from their raw params.
   */
  public static Map<String, Object> toPlainRequest(Object params) {
    Object raw = invokeIfPresent(params, "rawParams");
    return toPlainMap(invokeIfPresent(raw, "_body"));
  }

  /**
   * Returns an Anthropic SDK response as plain JSON. Structured outputs are read from their raw
   * message.
   */
  public static Map<String, Object> toPlainResponse(Object response) {
    return toPlainMap(invokeIfPresent(response, "rawMessage"));
  }

  /** Returns an Anthropic SDK object as plain JSON, or an empty map when it can't be read. */
  public static Map<String, Object> toPlainMap(Object value) {
    if (value == null) {
      return Collections.emptyMap();
    }
    try {
      byte[] json = ObjectMappers.jsonMapper().writeValueAsBytes(value);
      return map(PLAIN_MAPPER.readValue(json, Object.class));
    } catch (Exception e) {
      return Collections.emptyMap();
    }
  }

  // Returns target.name(), or target itself when it has no such method.
  private static Object invokeIfPresent(Object target, String name) {
    if (target == null) {
      return null;
    }
    try {
      Method method = target.getClass().getMethod(name);
      return method.invoke(target);
    } catch (NoSuchMethodException e) {
      return target;
    } catch (Exception e) {
      return null;
    }
  }

  // ======================================================
  // ===============  Request  ============================
  // ======================================================

  /** The span name, {@code chat <model>}, as in Node. */
  public static String spanName(Map<String, Object> params) {
    Object model = params.get("model");
    return "chat " + (model == null ? "unknown" : model);
  }

  /** Attributes of a {@code messages.create} request, from its plain JSON body. */
  public static Map<String, Object> requestAttributes(
      String sourceName, Map<String, Object> params, boolean stream, GenAICaptureOptions capture) {
    Map<String, Object> attrs = new LinkedHashMap<String, Object>();
    attrs.put("gen_ai.operation.name", "chat");
    attrs.put("gen_ai.operation.source_name", sourceName);
    attrs.put("gen_ai.provider.name", PROVIDER_NAME);
    attrs.put(StatsigTraceConventions.ATTR_GEN_AI_SPAN_TYPE, "chat");
    put(attrs, "gen_ai.request.model", params.get("model"));
    put(attrs, "gen_ai.request.max_tokens", params.get("max_tokens"));
    put(attrs, "gen_ai.request.temperature", params.get("temperature"));
    put(attrs, "gen_ai.request.top_p", params.get("top_p"));
    put(attrs, "gen_ai.request.top_k", params.get("top_k"));
    put(attrs, "gen_ai.request.stop_sequences", params.get("stop_sequences"));
    if (stream || Boolean.TRUE.equals(params.get("stream"))) {
      attrs.put("gen_ai.request.stream", true);
    }
    if (capture.captureInputMessages()) {
      List<Object> messages = list(params.get("messages"));
      attrs.put(
          "gen_ai.input.messages",
          toInputMessages(messages == null ? Collections.emptyList() : messages));
    }
    if (capture.captureSystemInstructions() && params.get("system") != null) {
      attrs.put("gen_ai.system_instructions", toParts(params.get("system")));
    }
    if (capture.captureToolDefinitions()) {
      put(attrs, "gen_ai.tool.definitions", params.get("tools"));
    }
    return attrs;
  }

  // ======================================================
  // ===============  Response  ===========================
  // ======================================================

  /** Attributes of a message, including its usage, from its plain JSON. */
  public static Map<String, Object> responseAttributes(
      Map<String, Object> message, GenAICaptureOptions capture) {
    Map<String, Object> attrs = new LinkedHashMap<String, Object>();
    put(attrs, "gen_ai.response.id", message.get("id"));
    put(attrs, "gen_ai.response.model", message.get("model"));
    attrs.putAll(usageAttributes(map(message.get("usage"))));
    String stopReason = string(message.get("stop_reason"));
    if (stopReason != null && !stopReason.isEmpty()) {
      attrs.put("gen_ai.response.finish_reasons", Collections.singletonList(stopReason));
    }
    if (capture.captureOutputMessages()) {
      Object content = message.get("content");
      attrs.put(
          "gen_ai.output.messages",
          Collections.singletonList(
              GenAIMessages.outputMessage(
                  toParts(content == null ? Collections.emptyList() : content),
                  toFinishReason(stopReason))));
    }
    return attrs;
  }

  /**
   * Token usage attributes. Anthropic reports cache reads and writes apart from {@code
   * input_tokens}, so both count toward input tokens, and cache reads are also recorded as cached
   * input tokens, as in Node.
   */
  public static Map<String, Object> usageAttributes(Map<String, Object> usage) {
    Map<String, Object> attrs = new LinkedHashMap<String, Object>();
    long cachedTokens = count(usage.get("cache_read_input_tokens"));
    long inputTokens =
        count(usage.get("input_tokens"))
            + cachedTokens
            + count(usage.get("cache_creation_input_tokens"));
    long outputTokens = count(usage.get("output_tokens"));
    if (inputTokens > 0) {
      attrs.put("gen_ai.usage.input_tokens", inputTokens);
    }
    if (outputTokens > 0) {
      attrs.put("gen_ai.usage.output_tokens", outputTokens);
    }
    if (inputTokens + outputTokens > 0) {
      attrs.put("statsig.gen_ai.usage.total_tokens", inputTokens + outputTokens);
    }
    if (cachedTokens > 0) {
      attrs.put("statsig.gen_ai.usage.input_cached_tokens", cachedTokens);
    }
    return attrs;
  }

  // ======================================================
  // ===============  Streams  ============================
  // ======================================================

  /**
   * Rebuilds the final message from {@code createStreaming} events as plain JSON, a port of Node's
   * {@code aggregateStreamEvents}.
   */
  public static Map<String, Object> aggregateStreamEvents(List<Map<String, Object>> events) {
    Map<String, Object> message = new LinkedHashMap<String, Object>();
    Map<Integer, Map<String, Object>> content = new TreeMap<Integer, Map<String, Object>>();
    Map<Integer, StringBuilder> toolInputJson = new TreeMap<Integer, StringBuilder>();
    Map<String, Object> usage = new LinkedHashMap<String, Object>();

    for (Map<String, Object> event : events) {
      String type = string(event.get("type"));
      if (type == null) {
        continue;
      }
      Number index = number(event.get("index"));
      int i = index == null ? 0 : index.intValue();
      switch (type) {
        case "message_start":
          message.put("id", get(event, "message", "id"));
          message.put("model", get(event, "message", "model"));
          usage.clear();
          putAllNonNull(usage, map(get(event, "message", "usage")));
          break;
        case "content_block_start":
          content.put(i, new LinkedHashMap<String, Object>(map(event.get("content_block"))));
          break;
        case "content_block_delta":
          Map<String, Object> block = content.get(i);
          Map<String, Object> delta = map(event.get("delta"));
          if (block == null) {
            break;
          }
          String deltaType = string(delta.get("type"));
          if ("text_delta".equals(deltaType)) {
            block.put("text", append(block.get("text"), delta.get("text")));
          } else if ("thinking_delta".equals(deltaType)) {
            block.put("thinking", append(block.get("thinking"), delta.get("thinking")));
          } else if ("input_json_delta".equals(deltaType)) {
            StringBuilder json = toolInputJson.get(i);
            if (json == null) {
              json = new StringBuilder();
              toolInputJson.put(i, json);
            }
            Object partial = delta.get("partial_json");
            json.append(partial == null ? "" : partial.toString());
          }
          break;
        case "message_delta":
          Object stopReason = get(event, "delta", "stop_reason");
          if (stopReason != null) {
            message.put("stop_reason", stopReason);
          }
          // Null counts in a delta don't replace the counts from message_start.
          putAllNonNull(usage, map(event.get("usage")));
          break;
        default:
          break;
      }
    }

    for (Map.Entry<Integer, StringBuilder> json : toolInputJson.entrySet()) {
      Map<String, Object> block = content.get(json.getKey());
      if (block != null) {
        block.put("input", GenAIMessages.parseToolArguments(json.getValue().toString()));
      }
    }
    message.put("content", new ArrayList<Object>(content.values()));
    message.put("usage", usage);
    return message;
  }

  // ======================================================
  // ===============  Messages  ===========================
  // ======================================================

  private static List<Map<String, Object>> toInputMessages(List<Object> messages) {
    List<Map<String, Object>> input = new ArrayList<Map<String, Object>>();
    for (Object value : messages) {
      Map<String, Object> message = map(value);
      List<Map<String, Object>> parts = toParts(message.get("content"));
      boolean isToolResults = !parts.isEmpty();
      for (Map<String, Object> part : parts) {
        if (!"tool_call_response".equals(part.get("type"))) {
          isToolResults = false;
        }
      }
      String role;
      if (isToolResults) {
        role = GenAIMessages.ROLE_TOOL;
      } else if ("assistant".equals(message.get("role"))) {
        role = GenAIMessages.ROLE_ASSISTANT;
      } else {
        role = GenAIMessages.ROLE_USER;
      }
      input.add(GenAIMessages.inputMessage(role, parts));
    }
    return input;
  }

  private static List<Map<String, Object>> toParts(Object content) {
    List<Map<String, Object>> parts = new ArrayList<Map<String, Object>>();
    if (content instanceof String) {
      parts.add(GenAIMessages.textPart((String) content));
      return parts;
    }
    List<Object> blocks = list(content);
    if (blocks == null) {
      return parts;
    }
    for (Object value : blocks) {
      Map<String, Object> block = map(value);
      String type = string(block.get("type"));
      if (type == null) {
        continue;
      }
      switch (type) {
        case "text":
          parts.add(GenAIMessages.textPart(string(block.get("text"))));
          break;
        case "thinking":
          parts.add(GenAIMessages.reasoningPart(string(block.get("thinking"))));
          break;
        case "tool_use":
        case "server_tool_use":
          parts.add(
              GenAIMessages.toolCallPart(
                  string(block.get("id")), string(block.get("name")), block.get("input")));
          break;
        case "tool_result":
          parts.add(
              GenAIMessages.toolCallResponsePart(
                  string(block.get("tool_use_id")), toolResult(block.get("content"))));
          break;
        case "image":
        case "document":
          if ("url".equals(get(block, "source", "type"))) {
            parts.add(GenAIMessages.uriPart(type, string(get(block, "source", "url")), null));
          } else {
            parts.add(GenAIMessages.blobPart(type, string(get(block, "source", "media_type"))));
          }
          break;
        default:
          break;
      }
    }
    return parts;
  }

  // A tool result's text, as Node joins it.
  private static String toolResult(Object content) {
    if (content instanceof String) {
      return (String) content;
    }
    StringBuilder text = new StringBuilder();
    for (Map<String, Object> part : toParts(content == null ? Collections.emptyList() : content)) {
      Object partContent = part.get("content");
      if (partContent != null) {
        text.append(partContent);
      }
    }
    return text.toString();
  }

  private static String toFinishReason(String stopReason) {
    if (stopReason == null) {
      return "stop";
    }
    switch (stopReason) {
      case "end_turn":
      case "stop_sequence":
        return "stop";
      case "max_tokens":
        return "length";
      case "tool_use":
        return "tool_call";
      case "refusal":
        return "content_filter";
      default:
        return stopReason;
    }
  }

  // ======================================================
  // ===============  Helpers  ============================
  // ======================================================

  private static long count(Object value) {
    Number n = number(value);
    return n == null ? 0L : n.longValue();
  }

  private static String append(Object current, Object addition) {
    return (current == null ? "" : current.toString())
        + (addition == null ? "" : addition.toString());
  }

  private static void putAllNonNull(Map<String, Object> target, Map<String, Object> values) {
    for (Map.Entry<String, Object> entry : values.entrySet()) {
      if (entry.getValue() != null) {
        target.put(entry.getKey(), entry.getValue());
      }
    }
  }

  private static void put(Map<String, Object> attrs, String key, Object value) {
    if (value != null) {
      attrs.put(key, value);
    }
  }
}
