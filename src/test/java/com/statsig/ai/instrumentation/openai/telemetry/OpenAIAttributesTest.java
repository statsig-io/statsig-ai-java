package com.statsig.ai.instrumentation.openai.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.statsig.ai.instrumentation.openai.GenAICaptureOptions;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/** Ports the OpenAI cases of the Node SDK's provider wrapper tests, on plain JSON. */
public class OpenAIAttributesTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final GenAICaptureOptions CAPTURE_ALL =
      GenAICaptureOptions.builder().captureAll(true).build();
  private static final GenAICaptureOptions CAPTURE_NONE = GenAICaptureOptions.builder().build();

  private static final String CHAT_TOOL_CALL_REQUEST =
      "{\"model\":\"gpt-test\",\"messages\":["
          + "{\"role\":\"system\",\"content\":\"You are helpful.\"},"
          + "{\"role\":\"user\",\"content\":\"Is checkout on?\"},"
          + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_1\","
          + "\"type\":\"function\",\"function\":{\"name\":\"list_gates\",\"arguments\":\"{}\"}}]},"
          + "{\"role\":\"tool\",\"tool_call_id\":\"call_1\",\"content\":\"[\\\"checkout\\\"]\"}]}";

  private static final String CHAT_TOOL_CALL_RESPONSE =
      "{\"id\":\"chatcmpl_1\",\"model\":\"gpt-test\",\"choices\":[{\"index\":0,"
          + "\"message\":{\"role\":\"assistant\",\"content\":null,"
          + "\"tool_calls\":[{\"id\":\"call_2\","
          + "\"type\":\"function\",\"function\":{\"name\":\"get_gate\","
          + "\"arguments\":\"{\\\"id\\\":\\\"checkout\\\"}\"}}]},"
          + "\"finish_reason\":\"tool_calls\"}],"
          + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5}}";

  private static final String TOOL_TURN_INPUT_MESSAGES =
      "[{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"content\":\"Is checkout on?\"}]},"
          + "{\"role\":\"assistant\",\"parts\":[{\"type\":\"tool_call\",\"id\":\"call_1\","
          + "\"name\":\"list_gates\",\"arguments\":{}}]},"
          + "{\"role\":\"tool\",\"parts\":[{\"type\":\"tool_call_response\",\"id\":\"call_1\","
          + "\"response\":\"[\\\"checkout\\\"]\"}]}]";

  @Test
  public void recordsChatCompletionsToolCallsInTheOpenTelemetryFormat() throws Exception {
    Map<String, Object> request =
        OpenAIAttributes.requestAttributes(
            "openai.chat.completions.create", plain(CHAT_TOOL_CALL_REQUEST), CAPTURE_ALL);
    Map<String, Object> response =
        OpenAIAttributes.responseAttributes(plain(CHAT_TOOL_CALL_RESPONSE), CAPTURE_ALL);

    assertEquals("openai", request.get("gen_ai.provider.name"));
    assertEquals(
        parse("[{\"type\":\"text\",\"content\":\"You are helpful.\"}]"),
        request.get("gen_ai.system_instructions"));
    assertEquals(parse(TOOL_TURN_INPUT_MESSAGES), request.get("gen_ai.input.messages"));
    assertEquals(
        parse(
            "[{\"role\":\"assistant\",\"parts\":[{\"type\":\"tool_call\",\"id\":\"call_2\","
                + "\"name\":\"get_gate\",\"arguments\":{\"id\":\"checkout\"}}],"
                + "\"finish_reason\":\"tool_call\"}]"),
        response.get("gen_ai.output.messages"));
    assertEquals(Arrays.asList("tool_calls"), response.get("gen_ai.response.finish_reasons"));
  }

  @Test
  public void recordsResponsesApiInstructionsInputItemsAndOutput() throws Exception {
    String requestJson =
        "{\"model\":\"gpt-test\",\"instructions\":\"Be brief.\",\"input\":["
            + "{\"role\":\"user\",\"content\":["
            + "{\"type\":\"input_text\",\"text\":\"Is checkout on?\"}]},"
            + "{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"list_gates\","
            + "\"arguments\":\"{}\"},"
            + "{\"type\":\"function_call_output\",\"call_id\":\"call_1\","
            + "\"output\":\"[\\\"checkout\\\"]\"}]}";
    String responseJson =
        "{\"id\":\"resp_1\",\"model\":\"gpt-test\",\"status\":\"completed\",\"output\":["
            + "{\"type\":\"reasoning\",\"summary\":[{\"type\":\"summary_text\","
            + "\"text\":\"Gate is listed.\"}]},"
            + "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\","
            + "\"text\":\"Checkout is on.\"}]}],"
            + "\"usage\":{\"input_tokens\":20,\"output_tokens\":8}}";

    Map<String, Object> request =
        OpenAIAttributes.requestAttributes(
            "openai.responses.create", plain(requestJson), CAPTURE_ALL);
    Map<String, Object> response =
        OpenAIAttributes.responseAttributes(plain(responseJson), CAPTURE_ALL);

    assertEquals("generate_content", request.get("gen_ai.operation.name"));
    assertEquals(
        parse("[{\"type\":\"text\",\"content\":\"Be brief.\"}]"),
        request.get("gen_ai.system_instructions"));
    assertEquals(parse(TOOL_TURN_INPUT_MESSAGES), request.get("gen_ai.input.messages"));
    assertEquals(
        parse(
            "[{\"role\":\"assistant\",\"parts\":["
                + "{\"type\":\"reasoning\",\"content\":\"Gate is listed.\"},"
                + "{\"type\":\"text\",\"content\":\"Checkout is on.\"}],"
                + "\"finish_reason\":\"stop\"}]"),
        response.get("gen_ai.output.messages"));
    assertEquals(20L, ((Number) response.get("gen_ai.usage.input_tokens")).longValue());
    assertEquals(28L, response.get("statsig.gen_ai.usage.total_tokens"));
  }

  @Test
  public void requestAttributesMatchNode() throws Exception {
    Map<String, Object> attrs =
        OpenAIAttributes.requestAttributes(
            "openai.chat.completions.create",
            plain(
                "{\"model\":\"gpt-4o\",\"max_completion_tokens\":256,\"temperature\":0.25,"
                    + "\"top_p\":0.5,\"frequency_penalty\":0.1,\"presence_penalty\":0.2,"
                    + "\"stop\":[\"END\"],\"n\":2,\"seed\":7,"
                    + "\"response_format\":{\"type\":\"json_object\"},\"service_tier\":\"flex\","
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}"),
            CAPTURE_NONE);

    assertEquals("gpt-4o", attrs.get("gen_ai.request.model"));
    assertEquals(256, attrs.get("gen_ai.request.max_tokens"));
    assertEquals(0.25, attrs.get("gen_ai.request.temperature"));
    assertEquals(0.5, attrs.get("gen_ai.request.top_p"));
    assertEquals(0.1, attrs.get("gen_ai.request.frequency_penalty"));
    assertEquals(0.2, attrs.get("gen_ai.request.presence_penalty"));
    assertEquals(Arrays.asList("END"), attrs.get("gen_ai.request.stop_sequences"));
    assertEquals(2, attrs.get("gen_ai.request.choice.count"));
    assertEquals(7, attrs.get("gen_ai.request.seed"));
    assertEquals("json_object", attrs.get("gen_ai.output.type"));
    assertEquals("chat", attrs.get("gen_ai.operation.name"));
    assertEquals("openai.chat.completions.create", attrs.get("gen_ai.operation.source_name"));
    assertEquals("openai", attrs.get("gen_ai.provider.name"));
    assertEquals("chat", attrs.get("statsig.span.gen_ai.type"));
    assertEquals("flex", attrs.get("openai.request.service_tier"));
    assertFalse(attrs.containsKey("gen_ai.request.encoding_formats"));
    assertEquals(
        "chat gpt-4o",
        OpenAIAttributes.spanName(
            "openai.chat.completions.create", plain("{\"model\":\"gpt-4o\"}")));
    assertEquals(
        "chat unknown",
        OpenAIAttributes.spanName(
            "openai.chat.completions.create", Collections.<String, Object>emptyMap()));
  }

  @Test
  public void defaultServiceTierAndOneChoiceAreLeftOut() throws Exception {
    Map<String, Object> attrs =
        OpenAIAttributes.requestAttributes(
            "openai.chat.completions.create",
            plain("{\"model\":\"gpt-4o\",\"n\":1,\"service_tier\":\"auto\",\"max_tokens\":9}"),
            CAPTURE_NONE);

    assertFalse(attrs.containsKey("gen_ai.request.choice.count"));
    assertFalse(attrs.containsKey("openai.request.service_tier"));
    assertEquals(9, attrs.get("gen_ai.request.max_tokens"));
  }

  @Test
  public void contentIsLeftOutWithoutCaptureOptions() throws Exception {
    String messages =
        CHAT_TOOL_CALL_REQUEST.substring(CHAT_TOOL_CALL_REQUEST.indexOf("\"messages\""));
    Map<String, Object> request =
        OpenAIAttributes.requestAttributes(
            "openai.chat.completions.create",
            plain(
                "{\"model\":\"gpt-test\",\"tools\":[{\"type\":\"function\","
                    + "\"function\":{\"name\":\"list_gates\"}}],"
                    + messages),
            CAPTURE_NONE);
    Map<String, Object> response =
        OpenAIAttributes.responseAttributes(plain(CHAT_TOOL_CALL_RESPONSE), CAPTURE_NONE);

    assertFalse(request.containsKey("gen_ai.input.messages"));
    assertFalse(request.containsKey("gen_ai.system_instructions"));
    assertFalse(request.containsKey("gen_ai.tool.definitions"));
    assertFalse(response.containsKey("gen_ai.output.messages"));
  }

  @Test
  public void toolDefinitionsAreTheRequestTools() throws Exception {
    Map<String, Object> attrs =
        OpenAIAttributes.requestAttributes(
            "openai.chat.completions.create",
            plain(
                "{\"model\":\"gpt-test\",\"tools\":[{\"type\":\"function\","
                    + "\"function\":{\"name\":\"list_gates\"}}]}"),
            GenAICaptureOptions.builder().captureToolDefinitions(true).build());

    assertEquals(
        parse("[{\"type\":\"function\",\"function\":{\"name\":\"list_gates\"}}]"),
        attrs.get("gen_ai.tool.definitions"));
  }

  @Test
  public void usageAttributesIncludeDetailsAndTotalAndDropZeros() throws Exception {
    Map<String, Object> chat =
        OpenAIAttributes.usageAttributes(
            plain(
                "{\"prompt_tokens\":40,\"completion_tokens\":10,"
                    + "\"prompt_tokens_details\":{\"cached_tokens\":4,\"audio_tokens\":0},"
                    + "\"completion_tokens_details\":{\"reasoning_tokens\":3}}"));
    Map<String, Object> expected = new LinkedHashMap<String, Object>();
    expected.put("gen_ai.usage.input_tokens", 40);
    expected.put("gen_ai.usage.output_tokens", 10);
    expected.put("statsig.gen_ai.usage.total_tokens", 50L);
    expected.put("statsig.gen_ai.usage.input_cached_tokens", 4);
    expected.put("statsig.gen_ai.usage.output_reasoning_tokens", 3);
    assertEquals(expected, chat);

    Map<String, Object> responses =
        OpenAIAttributes.usageAttributes(
            plain(
                "{\"input_tokens\":0,\"output_tokens\":6,"
                    + "\"input_tokens_details\":{\"cached_tokens\":0,\"audio_tokens\":2},"
                    + "\"output_tokens_details\":{\"reasoning_tokens\":0}}"));
    Map<String, Object> expectedResponses = new LinkedHashMap<String, Object>();
    expectedResponses.put("gen_ai.usage.output_tokens", 6);
    expectedResponses.put("statsig.gen_ai.usage.total_tokens", 6L);
    expectedResponses.put("statsig.gen_ai.usage.input_audio_tokens", 2);
    assertEquals(expectedResponses, responses);

    assertEquals(
        Collections.emptyMap(),
        OpenAIAttributes.usageAttributes(Collections.<String, Object>emptyMap()));
  }

  @Test
  public void outputMessagesMapFinishReasonsTextAndRefusals() throws Exception {
    assertEquals(
        parse(
            "[{\"role\":\"assistant\",\"parts\":[{\"type\":\"text\",\"content\":\"Hi\"}],"
                + "\"finish_reason\":\"stop\"},"
                + "{\"role\":\"assistant\",\"parts\":[{\"type\":\"text\",\"content\":\"No.\"}],"
                + "\"finish_reason\":\"length\"},"
                + "{\"role\":\"assistant\",\"parts\":[{\"type\":\"text\",\"content\":\"done\"}],"
                + "\"finish_reason\":\"tool_call\"}]"),
        OpenAIMessages.toOutputMessages(
            plain(
                "{\"choices\":["
                    + "{\"message\":{\"content\":\"Hi\"},\"finish_reason\":null},"
                    + "{\"message\":{\"content\":null,\"refusal\":\"No.\"},"
                    + "\"finish_reason\":\"length\"},"
                    + "{\"text\":\"done\",\"finish_reason\":\"function_call\"}]}")));
    assertNull(OpenAIMessages.toOutputMessages(plain("{\"data\":[]}")));
  }

  @Test
  public void incompleteResponsesFinishWithLengthOrContentFilter() throws Exception {
    assertEquals(
        "length",
        finishReason(
            "{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},"
                + "\"output\":[]}"));
    assertEquals(
        "content_filter",
        finishReason(
            "{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"content_filter\"},"
                + "\"output\":[]}"));
    assertEquals(
        "tool_call",
        finishReason(
            "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\","
                + "\"call_id\":\"c\",\"name\":\"f\",\"arguments\":\"not json\"}]}"));
  }

  @Test
  public void contentPartsCoverImagesAudioAndFiles() throws Exception {
    OpenAIMessages.RequestMessages messages =
        OpenAIMessages.toRequestMessages(
            plain(
                "{\"messages\":[{\"role\":\"user\",\"content\":["
                    + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://x/cat.png\"}},"
                    + "{\"type\":\"input_image\"},"
                    + "{\"type\":\"input_audio\",\"input_audio\":{\"format\":\"wav\"}},"
                    + "{\"type\":\"file\"},{\"type\":\"unknown\"}]},"
                    + "{\"role\":\"developer\","
                    + "\"content\":[{\"type\":\"text\",\"text\":\"Rules\"}]}]}"));

    assertEquals(
        parse(
            "[{\"role\":\"user\",\"parts\":["
                + "{\"type\":\"uri\",\"modality\":\"image\",\"uri\":\"https://x/cat.png\"},"
                + "{\"type\":\"blob\",\"modality\":\"image\"},"
                + "{\"type\":\"blob\",\"modality\":\"audio\",\"mime_type\":\"wav\"},"
                + "{\"type\":\"blob\",\"modality\":\"file\"}]}]"),
        messages.getInput());
    assertEquals(
        parse("[{\"type\":\"text\",\"content\":\"Rules\"}]"), messages.getSystemInstructions());
  }

  @Test
  public void stringPromptsBecomeUserMessages() throws Exception {
    assertEquals(
        parse("[{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"content\":\"Say hi\"}]}]"),
        OpenAIMessages.toRequestMessages(plain("{\"prompt\":\"Say hi\"}")).getInput());
  }

  private static String finishReason(String responseJson) throws Exception {
    return (String)
        OpenAIMessages.toOutputMessages(plain(responseJson)).get(0).get("finish_reason");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> plain(String json) throws Exception {
    return MAPPER.readValue(json, Map.class);
  }

  private static Object parse(String json) throws Exception {
    return MAPPER.readValue(json, Object.class);
  }
}
