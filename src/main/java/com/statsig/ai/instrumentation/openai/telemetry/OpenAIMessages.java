package com.statsig.ai.instrumentation.openai.telemetry;

import static com.statsig.ai.instrumentation.PlainJson.firstNonNull;
import static com.statsig.ai.instrumentation.PlainJson.get;
import static com.statsig.ai.instrumentation.PlainJson.list;
import static com.statsig.ai.instrumentation.PlainJson.map;
import static com.statsig.ai.instrumentation.PlainJson.string;

import com.statsig.ai.instrumentation.GenAIMessages;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts OpenAI requests and responses, as plain JSON in the API's wire format, into
 * OpenTelemetry GenAI messages. A port of {@code openai-messages.ts} in the Node SDK.
 */
public final class OpenAIMessages {
  private OpenAIMessages() {}

  private static final Set<String> SYSTEM_ROLES =
      new HashSet<String>(Arrays.asList("system", "developer"));

  /** The input messages and system instructions of a request. */
  public static final class RequestMessages {
    private final List<Map<String, Object>> input;
    private final List<Map<String, Object>> systemInstructions;

    RequestMessages(List<Map<String, Object>> input, List<Map<String, Object>> systemInstructions) {
      this.input = input;
      this.systemInstructions = systemInstructions;
    }

    /** Input messages, without system and developer messages. */
    public List<Map<String, Object>> getInput() {
      return input;
    }

    /** Parts of system and developer messages, and of the Responses {@code instructions}. */
    public List<Map<String, Object>> getSystemInstructions() {
      return systemInstructions;
    }
  }

  /**
   * Converts a Chat Completions, Responses, completions or embeddings request. System and developer
   * messages (and Responses {@code instructions}) become system instructions instead of input
   * messages.
   */
  public static RequestMessages toRequestMessages(Map<String, Object> params) {
    List<Map<String, Object>> systemInstructions = new ArrayList<Map<String, Object>>();
    String instructions = string(params.get("instructions"));
    if (instructions != null) {
      systemInstructions.add(GenAIMessages.textPart(instructions));
    }
    List<Map<String, Object>> input = new ArrayList<Map<String, Object>>();

    Object items =
        firstNonNull(
            params.get("messages"),
            params.get("input"),
            params.get("prompt"),
            Collections.emptyList());
    List<Object> itemList = list(items);
    for (Object item : itemList != null ? itemList : Collections.singletonList(items)) {
      if (item instanceof String) {
        input.add(
            GenAIMessages.inputMessage(
                GenAIMessages.ROLE_USER,
                Collections.singletonList(GenAIMessages.textPart((String) item))));
        continue;
      }
      if (SYSTEM_ROLES.contains(string(get(item, "role")))) {
        systemInstructions.addAll(toContentParts(get(item, "content")));
        continue;
      }
      Map<String, Object> message = toInputMessage(map(item));
      if (message != null) {
        input.add(message);
      }
    }
    return new RequestMessages(input, systemInstructions);
  }

  private static Map<String, Object> toInputMessage(Map<String, Object> item) {
    String type = string(item.get("type"));
    if ("function_call".equals(type)) {
      return GenAIMessages.inputMessage(
          GenAIMessages.ROLE_ASSISTANT, Collections.singletonList(toFunctionCallPart(item)));
    }
    if ("function_call_output".equals(type)) {
      return GenAIMessages.inputMessage(
          GenAIMessages.ROLE_TOOL,
          Collections.singletonList(
              GenAIMessages.toolCallResponsePart(string(item.get("call_id")), item.get("output"))));
    }
    if ("reasoning".equals(type)) {
      return GenAIMessages.inputMessage(GenAIMessages.ROLE_ASSISTANT, toReasoningParts(item));
    }

    String role = string(item.get("role"));
    if ("tool".equals(role) || "function".equals(role)) {
      return GenAIMessages.inputMessage(
          GenAIMessages.ROLE_TOOL,
          Collections.singletonList(
              GenAIMessages.toolCallResponsePart(
                  string(firstNonNull(item.get("tool_call_id"), item.get("name"))),
                  item.get("content"))));
    }
    if ("assistant".equals(role)) {
      List<Map<String, Object>> parts = toContentParts(item.get("content"));
      parts.addAll(toChatToolCallParts(item.get("tool_calls")));
      return GenAIMessages.inputMessage(GenAIMessages.ROLE_ASSISTANT, parts);
    }
    if ("user".equals(role)) {
      return GenAIMessages.inputMessage(
          GenAIMessages.ROLE_USER, toContentParts(item.get("content")));
    }
    return null;
  }

  /**
   * Converts Chat Completions {@code choices} (including text completions) or a Responses {@code
   * output} list into output messages. Returns null for responses without messages, such as
   * embeddings.
   */
  public static List<Map<String, Object>> toOutputMessages(Map<String, Object> response) {
    List<Object> choices = list(response.get("choices"));
    if (choices != null) {
      List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
      for (Object choiceValue : choices) {
        Map<String, Object> choice = map(choiceValue);
        Map<String, Object> message = map(choice.get("message"));
        List<Map<String, Object>> parts = new ArrayList<Map<String, Object>>();
        String text = string(choice.get("text"));
        if (text != null) {
          parts.add(GenAIMessages.textPart(text));
        }
        parts.addAll(toContentParts(message.get("content")));
        String refusal = string(message.get("refusal"));
        if (refusal != null && !refusal.isEmpty()) {
          parts.add(GenAIMessages.textPart(refusal));
        }
        parts.addAll(toChatToolCallParts(message.get("tool_calls")));
        String finishReason = toFinishReason(string(choice.get("finish_reason")));
        messages.add(GenAIMessages.outputMessage(parts, finishReason));
      }
      return messages;
    }

    List<Object> output = list(response.get("output"));
    if (output != null) {
      List<Map<String, Object>> parts = new ArrayList<Map<String, Object>>();
      for (Object itemValue : output) {
        Map<String, Object> item = map(itemValue);
        String type = string(item.get("type"));
        if ("message".equals(type)) {
          parts.addAll(toContentParts(item.get("content")));
        } else if ("function_call".equals(type)) {
          parts.add(toFunctionCallPart(item));
        } else if ("reasoning".equals(type)) {
          parts.addAll(toReasoningParts(item));
        }
      }
      return Collections.singletonList(
          GenAIMessages.outputMessage(parts, toResponsesFinishReason(response, parts)));
    }
    return null;
  }

  private static List<Map<String, Object>> toContentParts(Object content) {
    List<Map<String, Object>> parts = new ArrayList<Map<String, Object>>();
    if (content instanceof String) {
      parts.add(GenAIMessages.textPart((String) content));
      return parts;
    }
    List<Object> items = list(content);
    if (items == null) {
      return parts;
    }
    for (Object itemValue : items) {
      Map<String, Object> part = map(itemValue);
      String type = string(part.get("type"));
      if (type == null) {
        continue;
      }
      switch (type) {
        case "text":
        case "input_text":
        case "output_text":
          parts.add(GenAIMessages.textPart(string(part.get("text"))));
          break;
        case "refusal":
          parts.add(GenAIMessages.textPart(string(part.get("refusal"))));
          break;
        case "image_url":
          parts.add(GenAIMessages.uriPart("image", string(get(part, "image_url", "url")), null));
          break;
        case "input_image":
          String imageUrl = string(part.get("image_url"));
          parts.add(
              imageUrl != null
                  ? GenAIMessages.uriPart("image", imageUrl, null)
                  : GenAIMessages.blobPart("image", null));
          break;
        case "input_audio":
          parts.add(GenAIMessages.blobPart("audio", string(get(part, "input_audio", "format"))));
          break;
        case "file":
        case "input_file":
          parts.add(GenAIMessages.blobPart("file", null));
          break;
        default:
          break;
      }
    }
    return parts;
  }

  private static List<Map<String, Object>> toChatToolCallParts(Object toolCalls) {
    List<Map<String, Object>> parts = new ArrayList<Map<String, Object>>();
    List<Object> calls = list(toolCalls);
    if (calls == null) {
      return parts;
    }
    for (Object call : calls) {
      parts.add(
          GenAIMessages.toolCallPart(
              string(get(call, "id")),
              string(get(call, "function", "name")),
              GenAIMessages.parseToolArguments(string(get(call, "function", "arguments")))));
    }
    return parts;
  }

  private static Map<String, Object> toFunctionCallPart(Map<String, Object> item) {
    return GenAIMessages.toolCallPart(
        string(item.get("call_id")),
        string(item.get("name")),
        GenAIMessages.parseToolArguments(string(item.get("arguments"))));
  }

  private static List<Map<String, Object>> toReasoningParts(Map<String, Object> item) {
    List<Map<String, Object>> parts = new ArrayList<Map<String, Object>>();
    List<Object> summaries = list(item.get("summary"));
    if (summaries == null) {
      return parts;
    }
    for (Object summary : summaries) {
      parts.add(GenAIMessages.reasoningPart(string(get(summary, "text"))));
    }
    return parts;
  }

  private static String toFinishReason(String finishReason) {
    if (finishReason == null) {
      return "stop";
    }
    if ("tool_calls".equals(finishReason) || "function_call".equals(finishReason)) {
      return "tool_call";
    }
    return finishReason;
  }

  private static String toResponsesFinishReason(
      Map<String, Object> response, List<Map<String, Object>> parts) {
    if ("incomplete".equals(response.get("status"))) {
      return "content_filter".equals(get(response, "incomplete_details", "reason"))
          ? "content_filter"
          : "length";
    }
    for (Map<String, Object> part : parts) {
      if ("tool_call".equals(part.get("type"))) {
        return "tool_call";
      }
    }
    return "stop";
  }
}
