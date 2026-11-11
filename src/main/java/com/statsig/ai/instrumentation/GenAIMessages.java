package com.statsig.ai.instrumentation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds messages in the OpenTelemetry GenAI format, which every provider wrapper writes to {@code
 * gen_ai.input.messages}, {@code gen_ai.output.messages} and {@code gen_ai.system_instructions}, so
 * traces from every provider share one shape. Messages and parts are plain maps, which span
 * attributes record as JSON. Null fields are left out, as {@code JSON.stringify} leaves out {@code
 * undefined} in the Node SDK.
 *
 * <p>See https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-spans/.
 */
public final class GenAIMessages {
  private GenAIMessages() {}

  public static final String ROLE_SYSTEM = "system";
  public static final String ROLE_USER = "user";
  public static final String ROLE_ASSISTANT = "assistant";
  public static final String ROLE_TOOL = "tool";

  /** An input message: {@code {role, parts}}. */
  public static Map<String, Object> inputMessage(String role, List<Map<String, Object>> parts) {
    Map<String, Object> message = new LinkedHashMap<String, Object>();
    message.put("role", role);
    message.put("parts", parts);
    return message;
  }

  /** An output message: {@code {role: "assistant", parts, finish_reason}}. */
  public static Map<String, Object> outputMessage(
      List<Map<String, Object>> parts, String finishReason) {
    Map<String, Object> message = new LinkedHashMap<String, Object>();
    message.put("role", ROLE_ASSISTANT);
    message.put("parts", parts);
    message.put("finish_reason", finishReason);
    return message;
  }

  /** {@code {type: "text", content}}. */
  public static Map<String, Object> textPart(String content) {
    return part("text", "content", content);
  }

  /** {@code {type: "reasoning", content}}. */
  public static Map<String, Object> reasoningPart(String content) {
    return part("reasoning", "content", content);
  }

  /** {@code {type: "tool_call", id, name, arguments}}. */
  public static Map<String, Object> toolCallPart(String id, String name, Object arguments) {
    Map<String, Object> part = part("tool_call", "id", id);
    putIfNotNull(part, "name", name);
    putIfNotNull(part, "arguments", arguments);
    return part;
  }

  /** {@code {type: "tool_call_response", id, response}}. */
  public static Map<String, Object> toolCallResponsePart(String id, Object response) {
    Map<String, Object> part = part("tool_call_response", "id", id);
    putIfNotNull(part, "response", response);
    return part;
  }

  /** {@code {type: "blob", modality, mime_type}}, for content that isn't recorded. */
  public static Map<String, Object> blobPart(String modality, String mimeType) {
    Map<String, Object> part = part("blob", "modality", modality);
    putIfNotNull(part, "mime_type", mimeType);
    return part;
  }

  /** {@code {type: "uri", modality, uri, mime_type}}. */
  public static Map<String, Object> uriPart(String modality, String uri, String mimeType) {
    Map<String, Object> part = part("uri", "modality", modality);
    putIfNotNull(part, "uri", uri);
    putIfNotNull(part, "mime_type", mimeType);
    return part;
  }

  /**
   * Parses a tool call's JSON arguments, so they're recorded as JSON rather than a string. Returns
   * {@code json} itself when it isn't valid JSON.
   */
  public static Object parseToolArguments(String json) {
    return PlainJson.parseOrString(json == null ? "" : json);
  }

  private static Map<String, Object> part(String type, String key, Object value) {
    Map<String, Object> part = new LinkedHashMap<String, Object>();
    part.put("type", type);
    putIfNotNull(part, key, value);
    return part;
  }

  private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
    if (value != null) {
      map.put(key, value);
    }
  }
}
