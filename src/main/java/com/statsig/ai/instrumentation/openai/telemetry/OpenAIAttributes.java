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
   * can't be read. Structured-output params are read from their raw params.
   */
  public static Map<String, Object> toPlainRequest(Object params) {
    if (params == null) {
      return Collections.emptyMap();
    }
    Object raw = invokeIfPresent(params, "rawParams");
    Object body = invokeIfPresent(raw, "_body");
    return toPlainMap(body);
  }

  /**
   * Returns an OpenAI SDK response as plain JSON. Structured-output responses are read from their
   * raw response.
   */
  public static Map<String, Object> toPlainResponse(Object response) {
    Object raw = invokeIfPresent(response, "rawChatCompletion");
    raw = invokeIfPresent(raw, "rawResponse");
    return toPlainMap(raw);
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
   * Attributes of a streamed response, from its chunks or events as plain JSON, a port of Node's
   * {@code parseOAIStreamingResponseIntoAttributes}. Chat Completions deltas, including tool call
   * deltas, are joined into whole messages, and a Responses stream is read from the response on its
   * events. Usage is summed across chunks.
   */
  public static Map<String, Object> streamAttributes(
      List<Map<String, Object>> chunks, GenAICaptureOptions capture) {
    Map<String, Object> attrs = new LinkedHashMap<String, Object>();
    if (chunks.isEmpty()) {
      return attrs;
    }
    Object id = null;
    Object model = null;
    Map<String, Object> responsesResult = null;
    Map<String, Object> usageAttrs = new LinkedHashMap<String, Object>();
    Map<Long, StreamedChoice> choices = new LinkedHashMap<Long, StreamedChoice>();

    for (Map<String, Object> event : chunks) {
      // Responses stream events carry the response.
      Map<String, Object> chunk =
          event.get("response") instanceof Map ? map(event.get("response")) : event;
      id = firstNonNull(id, chunk.get("id"));
      model = firstNonNull(model, chunk.get("model"));
      if (chunk.get("output") instanceof List) {
        responsesResult = chunk;
      }
      for (Map.Entry<String, Object> usage : usageAttributes(map(chunk.get("usage"))).entrySet()) {
        Number previous = number(usageAttrs.get(usage.getKey()));
        long sum =
            (previous == null ? 0L : previous.longValue()) + number(usage.getValue()).longValue();
        usageAttrs.put(usage.getKey(), sum);
      }

      List<Object> chunkChoices = list(chunk.get("choices"));
      if (chunkChoices == null || chunkChoices.isEmpty()) {
        continue;
      }
      Map<String, Object> choice = map(chunkChoices.get(0));
      Number index = number(choice.get("index"));
      long key = index == null ? 0L : index.longValue();
      StreamedChoice streamed = choices.get(key);
      if (streamed == null) {
        streamed = new StreamedChoice(key);
        choices.put(key, streamed);
      }
      streamed.add(map(choice.get("delta")), choice.get("finish_reason"));
    }

    put(attrs, "gen_ai.response.id", id);
    put(attrs, "gen_ai.response.model", model);
    attrs.putAll(usageAttrs);
    List<Object> finishReasons = new ArrayList<Object>();
    List<Object> choiceList = new ArrayList<Object>();
    for (StreamedChoice choice : choices.values()) {
      finishReasons.add(choice.finishReason);
      choiceList.add(choice.toChoice());
    }
    attrs.put("gen_ai.response.finish_reasons", finishReasons);
    if (capture != null && capture.captureOutputMessages()) {
      Map<String, Object> result = responsesResult;
      if (result == null) {
        result = Collections.<String, Object>singletonMap("choices", choiceList);
      }
      put(attrs, "gen_ai.output.messages", OpenAIMessages.toOutputMessages(result));
    }
    return attrs;
  }

  // One choice's deltas, joined like Node's aggregateStreamedChoices.
  private static final class StreamedChoice {
    private final long index;
    private Object role;
    private final StringBuilder content = new StringBuilder();
    private final List<Map<String, Object>> toolCalls = new ArrayList<Map<String, Object>>();
    private Object finishReason;

    StreamedChoice(long index) {
      this.index = index;
    }

    void add(Map<String, Object> delta, Object finish) {
      if (delta.get("role") != null) {
        role = delta.get("role");
      }
      if (delta.get("content") instanceof String) {
        content.append((String) delta.get("content"));
      }
      List<Object> toolCallDeltas = list(delta.get("tool_calls"));
      if (toolCallDeltas != null) {
        for (Object toolCallDelta : toolCallDeltas) {
          addToolCall(map(toolCallDelta));
        }
      }
      if (finish != null) {
        finishReason = finish;
      }
    }

    private void addToolCall(Map<String, Object> delta) {
      Number deltaIndex = number(delta.get("index"));
      int i = deltaIndex == null ? 0 : deltaIndex.intValue();
      while (toolCalls.size() <= i) {
        toolCalls.add(null);
      }
      Map<String, Object> toolCall = toolCalls.get(i);
      if (toolCall == null) {
        toolCall = new LinkedHashMap<String, Object>();
        toolCall.put("type", "function");
        Map<String, Object> function = new LinkedHashMap<String, Object>();
        function.put("arguments", "");
        toolCall.put("function", function);
        toolCalls.set(i, toolCall);
      }
      Map<String, Object> function = map(toolCall.get("function"));
      if (toolCall.get("id") == null && delta.get("id") != null) {
        toolCall.put("id", delta.get("id"));
      }
      Object name = get(delta, "function", "name");
      if (function.get("name") == null && name != null) {
        function.put("name", name);
      }
      Object arguments = get(delta, "function", "arguments");
      function.put(
          "arguments", function.get("arguments") + (arguments == null ? "" : arguments.toString()));
    }

    Map<String, Object> toChoice() {
      Map<String, Object> message = new LinkedHashMap<String, Object>();
      message.put("role", role);
      message.put("content", content.toString());
      List<Map<String, Object>> calls = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> toolCall : toolCalls) {
        if (toolCall != null) {
          calls.add(toolCall);
        }
      }
      if (!calls.isEmpty()) {
        message.put("tool_calls", calls);
      }
      Map<String, Object> choice = new LinkedHashMap<String, Object>();
      choice.put("index", index);
      choice.put("message", message);
      choice.put("finish_reason", finishReason);
      return choice;
    }
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
