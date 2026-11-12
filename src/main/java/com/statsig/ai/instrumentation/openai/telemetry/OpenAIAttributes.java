package com.statsig.ai.instrumentation.openai.telemetry;

import static com.statsig.ai.instrumentation.PlainJson.firstNonNull;
import static com.statsig.ai.instrumentation.PlainJson.get;
import static com.statsig.ai.instrumentation.PlainJson.list;
import static com.statsig.ai.instrumentation.PlainJson.map;
import static com.statsig.ai.instrumentation.PlainJson.number;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.ObjectMappers;
import com.statsig.ai.instrumentation.openai.GenAICaptureOptions;
import com.statsig.ai.trace.StatsigTraceConventions;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads OpenAI span attributes from requests and responses, a port of {@code attribute-helper.ts}
 * and {@code openai-impl.ts} in the Node SDK.
 *
 * <p>SDK objects are first turned into plain JSON in the API's wire format with {@link
 * #toPlainRequest} and {@link #toPlain}, so they're read with the same keys as in Node. Message
 * content (input and output messages, system instructions and tool definitions) is recorded only
 * when {@link GenAICaptureOptions} asks for it.
 */
public final class OpenAIAttributes {

  private OpenAIAttributes() {}

  /** The tracer name for OpenAI model call spans. */
  public static final String INSTRUMENTATION_NAME = "com.statsig.ai.instrumentation.openai";

  public static final String PROVIDER_NAME = "openai";

  private static final ObjectMapper PLAIN_MAPPER = new ObjectMapper();

  private static final Map<String, String> OPERATION_NAMES;

  static {
    Map<String, String> names = new HashMap<String, String>();
    names.put("openai.chat.completions.create", "chat");
    names.put("openai.completions.create", "text_completion");
    names.put("openai.embeddings.create", "embeddings");
    names.put("openai.images.generate", "generate_content");
    names.put("openai.responses.create", "generate_content");
    names.put("openai.responses.stream", "generate_content");
    names.put("openai.responses.parse", "generate_content");
    OPERATION_NAMES = Collections.unmodifiableMap(names);
  }

  // ======================================================
  // ===============  Plain JSON  =========================
  // ======================================================

  /**
   * Returns the request body of an OpenAI SDK params object as plain JSON, or an empty map when it
   * can't be read.
   */
  public static Map<String, Object> toPlainRequest(Object params) {
    if (params == null) {
      return Collections.emptyMap();
    }
    Object body = params;
    try {
      Method bodyMethod = params.getClass().getMethod("_body");
      body = bodyMethod.invoke(params);
    } catch (Exception ignore) {
      // Params without a body are serialized as they are.
    }
    return toPlainMap(body);
  }

  /** {@link #toPlain} as a map, or an empty map when it isn't one. */
  public static Map<String, Object> toPlainMap(Object value) {
    return map(toPlain(value));
  }

  /**
   * Returns an OpenAI SDK object as plain JSON in the API's wire format: maps, lists, strings,
   * numbers, booleans and null. Returns null when it can't be serialized.
   */
  public static Object toPlain(Object value) {
    if (value == null) {
      return null;
    }
    try {
      byte[] json = ObjectMappers.jsonMapper().writeValueAsBytes(value);
      return PLAIN_MAPPER.readValue(json, Object.class);
    } catch (Exception e) {
      return null;
    }
  }

  // ======================================================
  // ===============  Names  ==============================
  // ======================================================

  /**
   * The OpenTelemetry operation for a source name, for example {@code chat} for {@code
   * openai.chat.completions.create}.
   */
  public static String operationName(String sourceName) {
    return OPERATION_NAMES.get(sourceName);
  }

  /** The span name, {@code <operation> <model>}, for example {@code chat gpt-4o}. */
  public static String spanName(String sourceName, Map<String, Object> params) {
    Object model = params.get("model");
    return operationName(sourceName) + " " + (model == null ? "unknown" : model);
  }

  // ======================================================
  // ===============  Request  ============================
  // ======================================================

  /** Attributes of a request, from its plain JSON body. */
  public static Map<String, Object> requestAttributes(
      String sourceName, Map<String, Object> params, GenAICaptureOptions capture) {
    if (capture == null) {
      capture = GenAICaptureOptions.builder().build();
    }
    Map<String, Object> attrs = new LinkedHashMap<String, Object>();
    put(attrs, "gen_ai.request.model", params.get("model"));
    put(
        attrs,
        "gen_ai.request.max_tokens",
        firstNonNull(params.get("max_tokens"), params.get("max_completion_tokens")));
    put(attrs, "gen_ai.request.temperature", params.get("temperature"));
    put(attrs, "gen_ai.request.top_p", params.get("top_p"));
    put(attrs, "gen_ai.request.top_k", params.get("top_k"));
    put(attrs, "gen_ai.request.frequency_penalty", params.get("frequency_penalty"));
    put(attrs, "gen_ai.request.presence_penalty", params.get("presence_penalty"));
    put(
        attrs,
        "gen_ai.request.stop_sequences",
        firstNonNull(params.get("stop"), params.get("stop_sequences")));
    Number n = number(params.get("n"));
    if (n != null && n.longValue() != 0 && n.longValue() != 1) {
      attrs.put("gen_ai.request.choice.count", n);
    }
    put(attrs, "gen_ai.request.seed", params.get("seed"));
    put(attrs, "gen_ai.output.type", get(params, "response_format", "type"));

    boolean captureInput = capture.captureInputMessages();
    boolean captureSystem = capture.captureSystemInstructions();
    if (captureInput || captureSystem) {
      OpenAIMessages.RequestMessages messages = OpenAIMessages.toRequestMessages(params);
      if (captureInput) {
        attrs.put("gen_ai.input.messages", messages.getInput());
      }
      if (captureSystem && !messages.getSystemInstructions().isEmpty()) {
        attrs.put("gen_ai.system_instructions", messages.getSystemInstructions());
      }
    }
    if (capture.captureToolDefinitions()) {
      put(attrs, "gen_ai.tool.definitions", params.get("tools"));
    }

    // Embeddings
    put(attrs, "gen_ai.embeddings.dimension.count", params.get("dimensions"));
    Object encodingFormat = params.get("encoding_format");
    if (encodingFormat != null) {
      attrs.put("gen_ai.request.encoding_formats", Collections.singletonList(encodingFormat));
    }

    // Images
    put(attrs, "statsig.gen_ai.images.output_compression", params.get("output_compression"));
    put(attrs, "statsig.gen_ai.images.output_format", params.get("output_format"));
    put(attrs, "statsig.gen_ai.images.quality", params.get("quality"));
    put(attrs, "statsig.gen_ai.images.size", params.get("size"));

    String operationName = operationName(sourceName);
    put(attrs, "gen_ai.operation.name", operationName);
    attrs.put("gen_ai.operation.source_name", sourceName);
    attrs.put("gen_ai.provider.name", PROVIDER_NAME);
    if ("chat".equals(operationName) || "generate_content".equals(operationName)) {
      attrs.put(StatsigTraceConventions.ATTR_GEN_AI_SPAN_TYPE, operationName);
    }
    Object serviceTier = firstNonNull(params.get("service_tier"), "auto");
    if (!"auto".equals(serviceTier)) {
      attrs.put("openai.request.service_tier", serviceTier);
    }
    return attrs;
  }

  // ======================================================
  // ===============  Response  ===========================
  // ======================================================

  /** Attributes of a response, including its usage, from its plain JSON. */
  public static Map<String, Object> responseAttributes(
      Map<String, Object> response, GenAICaptureOptions capture) {
    Map<String, Object> attrs = new LinkedHashMap<String, Object>();
    put(attrs, "gen_ai.response.id", response.get("id"));
    put(attrs, "gen_ai.response.model", response.get("model"));
    put(attrs, "gen_ai.conversation.id", response.get("conversation_id"));

    List<Object> outputs = list(firstNonNull(response.get("choices"), response.get("output")));
    if (outputs != null && !outputs.isEmpty()) {
      List<Object> finishReasons = new ArrayList<Object>();
      for (Object output : outputs) {
        finishReasons.add(get(output, "finish_reason"));
      }
      attrs.put("gen_ai.response.finish_reasons", finishReasons);
    }
    if (capture != null && capture.captureOutputMessages()) {
      put(attrs, "gen_ai.output.messages", OpenAIMessages.toOutputMessages(response));
    }

    attrs.putAll(usageAttributes(map(response.get("usage"))));
    return attrs;
  }

  /**
   * Token usage attributes. Input and output tokens are read from either the Chat Completions or
   * the Responses names. Counts of zero are left out, as in Node.
   */
  public static Map<String, Object> usageAttributes(Map<String, Object> usage) {
    Map<String, Object> attrs = new LinkedHashMap<String, Object>();

    Number inputTokens =
        number(firstNonNull(usage.get("input_tokens"), usage.get("prompt_tokens")));
    Number outputTokens =
        number(firstNonNull(usage.get("output_tokens"), usage.get("completion_tokens")));
    put(attrs, "gen_ai.usage.input_tokens", inputTokens);
    put(attrs, "gen_ai.usage.output_tokens", outputTokens);
    if (inputTokens != null || outputTokens != null) {
      attrs.put(
          "statsig.gen_ai.usage.total_tokens",
          (inputTokens == null ? 0L : inputTokens.longValue())
              + (outputTokens == null ? 0L : outputTokens.longValue()));
    }

    Object inputDetails =
        firstNonNull(usage.get("input_tokens_details"), usage.get("prompt_tokens_details"));
    put(
        attrs,
        "statsig.gen_ai.usage.input_cached_tokens",
        number(get(inputDetails, "cached_tokens")));
    put(
        attrs,
        "statsig.gen_ai.usage.input_audio_tokens",
        number(get(inputDetails, "audio_tokens")));

    Object outputDetails =
        firstNonNull(usage.get("output_tokens_details"), usage.get("completion_tokens_details"));
    put(
        attrs,
        "statsig.gen_ai.usage.output_reasoning_tokens",
        number(get(outputDetails, "reasoning_tokens")));

    for (Iterator<Object> it = attrs.values().iterator(); it.hasNext(); ) {
      Object value = it.next();
      if (value instanceof Number && ((Number) value).doubleValue() == 0) {
        it.remove();
      }
    }
    return attrs;
  }

  private static void put(Map<String, Object> attrs, String key, Object value) {
    if (value != null) {
      attrs.put(key, value);
    }
  }
}
