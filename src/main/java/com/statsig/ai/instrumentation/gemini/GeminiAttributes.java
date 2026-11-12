package com.statsig.ai.instrumentation.gemini;

import static com.statsig.ai.instrumentation.PlainJson.firstNonNull;
import static com.statsig.ai.instrumentation.PlainJson.get;
import static com.statsig.ai.instrumentation.PlainJson.list;
import static com.statsig.ai.instrumentation.PlainJson.map;
import static com.statsig.ai.instrumentation.PlainJson.number;
import static com.statsig.ai.instrumentation.PlainJson.string;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.JsonSerializable;
import com.statsig.ai.instrumentation.GenAIMessages;
import com.statsig.ai.instrumentation.openai.GenAICaptureOptions;
import com.statsig.ai.trace.StatsigTraceConventions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads Gemini span attributes from requests and responses, a port of the attribute code in the
 * Node SDK's {@code gemini.ts}. Google Gen AI SDK objects are first turned into plain JSON with
 * their own {@code toJson()}, so they're read with the same keys as in Node.
 */
public final class GeminiAttributes {
  private GeminiAttributes() {}

  /** The tracer name for Gemini model call spans. */
  public static final String INSTRUMENTATION_NAME = "com.statsig.ai.instrumentation.gemini";

  public static final String PROVIDER_NAME = "gcp.gen_ai";
  static final String SOURCE_GENERATE_CONTENT = "google.models.generate_content";
  static final String SOURCE_GENERATE_CONTENT_STREAM = "google.models.generate_content_stream";

  private static final ObjectMapper PLAIN_MAPPER = new ObjectMapper();

  // ======================================================
  // ===============  Plain JSON  =========================
  // ======================================================

  /**
   * Returns {@code generateContent}'s arguments as Node's params: {@code {model, contents,
   * config}}. Contents can be a string, a {@code Content} or a list of them.
   */
  public static Map<String, Object> toPlainRequest(String model, Object contents, Object config) {
    Map<String, Object> params = new LinkedHashMap<String, Object>();
    params.put("model", model);
    params.put("contents", toPlain(contents));
    if (config != null) {
      params.put("config", toPlain(config));
    }
    return params;
  }

  /**
   * Returns a Gen AI SDK object, a list of them, or a string as plain JSON. Returns null when it
   * can't be read.
   */
  public static Object toPlain(Object value) {
    if (value == null || value instanceof String) {
      return value;
    }
    if (value instanceof List) {
      List<Object> items = new ArrayList<Object>();
      for (Object item : (List<?>) value) {
        items.add(toPlain(item));
      }
      return items;
    }
    if (value instanceof JsonSerializable) {
      try {
        return PLAIN_MAPPER.readValue(((JsonSerializable) value).toJson(), Object.class);
      } catch (Exception e) {
        return null;
      }
    }
    return null;
  }

  // ======================================================
  // ===============  Request  ============================
  // ======================================================

  /** The span name, {@code generate_content <model>}, as in Node. */
  public static String spanName(Map<String, Object> params) {
    Object model = params.get("model");
    return "generate_content " + (model == null ? "unknown" : model);
  }

  /** Attributes of a {@code generateContent} request, from its plain JSON params. */
  public static Map<String, Object> requestAttributes(
      String sourceName, Map<String, Object> params, GenAICaptureOptions capture) {
    Map<String, Object> config = map(params.get("config"));
    Map<String, Object> attrs = new LinkedHashMap<String, Object>();
    attrs.put("gen_ai.operation.name", "generate_content");
    attrs.put("gen_ai.operation.source_name", sourceName);
    attrs.put("gen_ai.provider.name", PROVIDER_NAME);
    attrs.put(StatsigTraceConventions.ATTR_GEN_AI_SPAN_TYPE, "generate_content");
    put(attrs, "gen_ai.request.model", params.get("model"));
    put(attrs, "gen_ai.request.max_tokens", config.get("maxOutputTokens"));
    put(attrs, "gen_ai.request.temperature", config.get("temperature"));
    put(attrs, "gen_ai.request.top_p", config.get("topP"));
    put(attrs, "gen_ai.request.top_k", config.get("topK"));
    put(attrs, "gen_ai.request.stop_sequences", config.get("stopSequences"));
    put(attrs, "gen_ai.request.seed", config.get("seed"));
    if (SOURCE_GENERATE_CONTENT_STREAM.equals(sourceName)) {
      attrs.put("gen_ai.request.stream", true);
    }
    if (capture.captureInputMessages()) {
      attrs.put("gen_ai.input.messages", toInputMessages(params.get("contents")));
    }
    if (capture.captureSystemInstructions() && config.get("systemInstruction") != null) {
      List<Map<String, Object>> parts = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> message : toInputMessages(config.get("systemInstruction"))) {
        for (Object part : list(message.get("parts"))) {
          parts.add(map(part));
        }
      }
      attrs.put("gen_ai.system_instructions", parts);
    }
    if (capture.captureToolDefinitions()) {
      put(attrs, "gen_ai.tool.definitions", config.get("tools"));
    }
    return attrs;
  }

  // ======================================================
  // ===============  Response  ===========================
  // ======================================================

  /** Attributes of a response, including its usage, from its plain JSON. */
  public static Map<String, Object> responseAttributes(
      Map<String, Object> response, GenAICaptureOptions capture) {
    List<Object> candidates = list(response.get("candidates"));
    if (candidates == null) {
      candidates = Collections.emptyList();
    }
    Map<String, Object> attrs = new LinkedHashMap<String, Object>();
    put(attrs, "gen_ai.response.id", response.get("responseId"));
    put(attrs, "gen_ai.response.model", response.get("modelVersion"));
    attrs.putAll(usageAttributes(map(response.get("usageMetadata"))));

    List<Object> finishReasons = new ArrayList<Object>();
    for (Object candidate : candidates) {
      String finishReason = string(get(candidate, "finishReason"));
      if (finishReason != null && !finishReason.isEmpty()) {
        finishReasons.add(finishReason);
      }
    }
    if (!finishReasons.isEmpty()) {
      attrs.put("gen_ai.response.finish_reasons", finishReasons);
    }
    if (capture.captureOutputMessages()) {
      List<Map<String, Object>> output = new ArrayList<Map<String, Object>>();
      for (Object candidate : candidates) {
        List<Map<String, Object>> parts = toParts(get(candidate, "content", "parts"));
        String finishReason = toFinishReason(string(get(candidate, "finishReason")), parts);
        output.add(GenAIMessages.outputMessage(parts, finishReason));
      }
      attrs.put("gen_ai.output.messages", output);
    }
    return attrs;
  }

  /**
   * Token usage attributes. Thinking tokens count toward output tokens, and are also recorded as
   * reasoning tokens, as in Node. Cached input tokens come from {@code cachedContentTokenCount}.
   */
  public static Map<String, Object> usageAttributes(Map<String, Object> usage) {
    Map<String, Object> attrs = new LinkedHashMap<String, Object>();
    long inputTokens = count(usage.get("promptTokenCount"));
    long reasoningTokens = count(usage.get("thoughtsTokenCount"));
    long outputTokens = count(usage.get("candidatesTokenCount")) + reasoningTokens;
    long cachedTokens = count(usage.get("cachedContentTokenCount"));
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
    if (reasoningTokens > 0) {
      attrs.put("statsig.gen_ai.usage.output_reasoning_tokens", reasoningTokens);
    }
    return attrs;
  }

  // ======================================================
  // ===============  Streams  ============================
  // ======================================================

  /**
   * Merges {@code generateContentStream} chunks, as plain JSON, into one response, joining text
   * that arrives in pieces for each candidate. A port of Node's {@code aggregateStreamChunks}.
   */
  public static Map<String, Object> aggregateStreamChunks(List<Map<String, Object>> chunks) {
    Map<String, Object> response = new LinkedHashMap<String, Object>();
    List<Map<String, Object>> merged = new ArrayList<Map<String, Object>>();
    for (Map<String, Object> chunk : chunks) {
      response.put("responseId", firstNonNull(response.get("responseId"), chunk.get("responseId")));
      response.put(
          "modelVersion", firstNonNull(response.get("modelVersion"), chunk.get("modelVersion")));
      if (chunk.get("usageMetadata") != null) {
        response.put("usageMetadata", chunk.get("usageMetadata"));
      }
      List<Object> candidates = list(chunk.get("candidates"));
      if (candidates == null) {
        continue;
      }
      for (int i = 0; i < candidates.size(); i++) {
        while (merged.size() <= i) {
          Map<String, Object> content = new LinkedHashMap<String, Object>();
          content.put("role", "model");
          content.put("parts", new ArrayList<Object>());
          Map<String, Object> candidate = new LinkedHashMap<String, Object>();
          candidate.put("content", content);
          merged.add(candidate);
        }
        mergeCandidate(merged.get(i), map(candidates.get(i)));
      }
    }
    response.put("candidates", merged);
    return response;
  }

  @SuppressWarnings("unchecked")
  private static void mergeCandidate(Map<String, Object> merged, Map<String, Object> candidate) {
    if (candidate.get("finishReason") != null) {
      merged.put("finishReason", candidate.get("finishReason"));
    }
    List<Object> parts = (List<Object>) map(merged.get("content")).get("parts");
    List<Object> chunkParts = list(get(candidate, "content", "parts"));
    if (chunkParts == null) {
      return;
    }
    for (Object value : chunkParts) {
      Map<String, Object> part = map(value);
      Map<String, Object> last = parts.isEmpty() ? null : map(parts.get(parts.size() - 1));
      if (part.get("text") instanceof String
          && last != null
          && last.get("text") instanceof String
          && isThought(last) == isThought(part)) {
        last.put("text", (String) last.get("text") + part.get("text"));
      } else {
        parts.add(new LinkedHashMap<String, Object>(part));
      }
    }
  }

  // ======================================================
  // ===============  Messages  ===========================
  // ======================================================

  // contents can be a string, a part, a Content, or a list of either.
  private static List<Map<String, Object>> toInputMessages(Object contents) {
    List<Object> items =
        contents instanceof List ? list(contents) : Collections.singletonList(contents);
    boolean isContentList = false;
    for (Object item : items) {
      if (item instanceof Map && ((Map<?, ?>) item).containsKey("parts")) {
        isContentList = true;
      }
    }
    List<Object> contentList = new ArrayList<Object>();
    if (isContentList) {
      contentList.addAll(items);
    } else {
      List<Object> parts = new ArrayList<Object>();
      for (Object item : items) {
        parts.add(item instanceof String ? Collections.singletonMap("text", item) : item);
      }
      Map<String, Object> content = new LinkedHashMap<String, Object>();
      content.put("role", "user");
      content.put("parts", parts);
      contentList.add(content);
    }

    List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
    for (Object value : contentList) {
      Map<String, Object> content = map(value);
      List<Map<String, Object>> parts = toParts(content.get("parts"));
      boolean isToolResults = !parts.isEmpty();
      for (Map<String, Object> part : parts) {
        if (!"tool_call_response".equals(part.get("type"))) {
          isToolResults = false;
        }
      }
      String role;
      if (isToolResults) {
        role = GenAIMessages.ROLE_TOOL;
      } else if ("model".equals(content.get("role"))) {
        role = GenAIMessages.ROLE_ASSISTANT;
      } else {
        role = GenAIMessages.ROLE_USER;
      }
      messages.add(GenAIMessages.inputMessage(role, parts));
    }
    return messages;
  }

  private static List<Map<String, Object>> toParts(Object value) {
    List<Map<String, Object>> parts = new ArrayList<Map<String, Object>>();
    List<Object> items = list(value);
    if (items == null) {
      return parts;
    }
    for (Object item : items) {
      Map<String, Object> part = map(item);
      if (part.get("text") instanceof String) {
        String text = (String) part.get("text");
        parts.add(
            isThought(part) ? GenAIMessages.reasoningPart(text) : GenAIMessages.textPart(text));
      } else if (part.get("functionCall") instanceof Map) {
        Map<String, Object> call = map(part.get("functionCall"));
        parts.add(
            GenAIMessages.toolCallPart(
                string(firstNonNull(call.get("id"), call.get("name"))),
                string(call.get("name")),
                call.get("args")));
      } else if (part.get("functionResponse") instanceof Map) {
        Map<String, Object> response = map(part.get("functionResponse"));
        parts.add(
            GenAIMessages.toolCallResponsePart(
                string(firstNonNull(response.get("id"), response.get("name"))),
                response.get("response")));
      } else if (part.get("inlineData") instanceof Map) {
        String mimeType = string(get(part, "inlineData", "mimeType"));
        parts.add(GenAIMessages.blobPart(toModality(mimeType), mimeType));
      } else if (part.get("fileData") instanceof Map) {
        String mimeType = string(get(part, "fileData", "mimeType"));
        parts.add(
            GenAIMessages.uriPart(
                toModality(mimeType), string(get(part, "fileData", "fileUri")), mimeType));
      }
    }
    return parts;
  }

  private static String toModality(String mimeType) {
    return mimeType == null ? "unknown" : mimeType.split("/")[0];
  }

  private static String toFinishReason(String finishReason, List<Map<String, Object>> parts) {
    for (Map<String, Object> part : parts) {
      if ("tool_call".equals(part.get("type"))) {
        return "tool_call";
      }
    }
    if (finishReason == null) {
      return "stop";
    }
    switch (finishReason) {
      case "STOP":
        return "stop";
      case "MAX_TOKENS":
        return "length";
      case "SAFETY":
      case "RECITATION":
      case "BLOCKLIST":
      case "PROHIBITED_CONTENT":
      case "SPII":
        return "content_filter";
      default:
        return finishReason.toLowerCase(Locale.ROOT);
    }
  }

  // ======================================================
  // ===============  Helpers  ============================
  // ======================================================

  private static boolean isThought(Map<String, Object> part) {
    return Boolean.TRUE.equals(part.get("thought"));
  }

  private static long count(Object value) {
    Number n = number(value);
    return n == null ? 0L : n.longValue();
  }

  private static void put(Map<String, Object> attrs, String key, Object value) {
    if (value != null) {
      attrs.put(key, value);
    }
  }
}
