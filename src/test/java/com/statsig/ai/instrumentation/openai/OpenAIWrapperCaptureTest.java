package com.statsig.ai.instrumentation.openai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openai.client.OpenAIClient;
import com.openai.models.FunctionDefinition;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import com.statsig.ai.trace.singleton.OtelSingleton;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class OpenAIWrapperCaptureTest {
  private static final AttributeKey<String> INPUT_MESSAGES =
      AttributeKey.stringKey("gen_ai.input.messages");
  private static final AttributeKey<String> OUTPUT_MESSAGES =
      AttributeKey.stringKey("gen_ai.output.messages");
  private static final AttributeKey<String> SYSTEM_INSTRUCTIONS =
      AttributeKey.stringKey("gen_ai.system_instructions");
  private static final AttributeKey<String> TOOL_DEFINITIONS =
      AttributeKey.stringKey("gen_ai.tool.definitions");

  private InMemorySpanExporter spanExporter;
  private SdkTracerProvider tracerProvider;

  @Before
  public void setUp() throws Exception {
    spanExporter = InMemorySpanExporter.create();
    tracerProvider =
        SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
            .build();
    resetOtelSingleton();
    OtelSingleton.instantiate(tracerProvider);
  }

  @After
  public void tearDown() throws Exception {
    spanExporter.reset();
    tracerProvider.shutdown().join(5, TimeUnit.SECONDS);
    resetOtelSingleton();
  }

  @Test
  public void defaultConfigCapturesNoMessageContent() {
    Attributes attrs = callChatCompletions(null, "Hello!");

    assertNull(attrs.get(INPUT_MESSAGES));
    assertNull(attrs.get(OUTPUT_MESSAGES));
    assertNull(attrs.get(SYSTEM_INSTRUCTIONS));
    assertNull(attrs.get(TOOL_DEFINITIONS));
    // Attributes without message content are still recorded.
    assertEquals("gpt-4o-mini", attrs.get(AttributeKey.stringKey("gen_ai.request.model")));
    assertEquals("chatcmpl-1", attrs.get(AttributeKey.stringKey("gen_ai.response.id")));
    assertEquals("[\"stop\"]", attrs.get(AttributeKey.stringKey("gen_ai.response.finish_reasons")));
    assertEquals(Long.valueOf(12), attrs.get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
  }

  @Test
  public void wrapWithoutConfigCapturesNoMessageContent() {
    Attributes attrs = callWrapped(OpenAIWrapper.wrap(newClient()), "Hello!");

    assertNull(attrs.get(INPUT_MESSAGES));
    assertNull(attrs.get(OUTPUT_MESSAGES));
    assertNull(attrs.get(SYSTEM_INSTRUCTIONS));
    assertNull(attrs.get(TOOL_DEFINITIONS));
  }

  @Test
  public void captureInputMessagesCapturesOnlyInputMessages() {
    Attributes attrs =
        callChatCompletions(
            capture(GenAICaptureOptions.builder().captureInputMessages(true)), "Hello!");

    assertNotNull(attrs.get(INPUT_MESSAGES));
    assertTrue(attrs.get(INPUT_MESSAGES).contains("Hello!"));
    assertNull(attrs.get(OUTPUT_MESSAGES));
    assertNull(attrs.get(SYSTEM_INSTRUCTIONS));
    assertNull(attrs.get(TOOL_DEFINITIONS));
  }

  @Test
  public void captureOutputMessagesCapturesOnlyOutputMessages() {
    Attributes attrs =
        callChatCompletions(
            capture(GenAICaptureOptions.builder().captureOutputMessages(true)), "Hello!");

    assertNull(attrs.get(INPUT_MESSAGES));
    assertNotNull(attrs.get(OUTPUT_MESSAGES));
    assertTrue(attrs.get(OUTPUT_MESSAGES).contains("Hi there"));
    assertNull(attrs.get(SYSTEM_INSTRUCTIONS));
    assertNull(attrs.get(TOOL_DEFINITIONS));
  }

  @Test
  public void captureSystemInstructionsCapturesOnlySystemInstructions() {
    Attributes attrs =
        callChatCompletions(
            capture(GenAICaptureOptions.builder().captureSystemInstructions(true)), "Hello!");

    assertNull(attrs.get(INPUT_MESSAGES));
    assertNull(attrs.get(OUTPUT_MESSAGES));
    assertNotNull(attrs.get(SYSTEM_INSTRUCTIONS));
    assertTrue(attrs.get(SYSTEM_INSTRUCTIONS).contains("You are helpful"));
    assertNull(attrs.get(TOOL_DEFINITIONS));
  }

  @Test
  public void captureToolDefinitionsCapturesOnlyToolDefinitions() {
    Attributes attrs =
        callChatCompletions(
            capture(GenAICaptureOptions.builder().captureToolDefinitions(true)), "Hello!");

    assertNull(attrs.get(INPUT_MESSAGES));
    assertNull(attrs.get(OUTPUT_MESSAGES));
    assertNull(attrs.get(SYSTEM_INSTRUCTIONS));
    assertNotNull(attrs.get(TOOL_DEFINITIONS));
    assertTrue(attrs.get(TOOL_DEFINITIONS).contains("search_docs"));
  }

  @Test
  public void captureAllCapturesAllFour() {
    Attributes attrs =
        callChatCompletions(capture(GenAICaptureOptions.builder().captureAll(true)), "Hello!");

    assertNotNull(attrs.get(INPUT_MESSAGES));
    assertNotNull(attrs.get(OUTPUT_MESSAGES));
    assertNotNull(attrs.get(SYSTEM_INSTRUCTIONS));
    assertNotNull(attrs.get(TOOL_DEFINITIONS));
  }

  @Test
  public void longValuesAreTruncatedAtMaxJsonChars() {
    StringBuilder input = new StringBuilder();
    for (int i = 0; i < 100; i++) {
      input.append("long input ");
    }
    OpenAIWrapperConfig config =
        OpenAIWrapperConfig.builder()
            .setCaptureOptions(GenAICaptureOptions.builder().captureInputMessages(true).build())
            .setMaxJsonChars(50)
            .build();

    Attributes attrs = callChatCompletions(config, input.toString());

    String messages = attrs.get(INPUT_MESSAGES);
    assertEquals(50 + "…(truncated)".length(), messages.length());
    assertTrue(messages.endsWith("…(truncated)"));
    assertTrue(attrs.get(AttributeKey.longKey("gen_ai.input.messages_len")) > 1000L);
  }

  @Test
  public void maxJsonCharsDefaultsTo40000() {
    assertEquals(40_000, OpenAIWrapperConfig.builder().build().getMaxJsonChars());
    assertEquals(
        40_000, OpenAIWrapperConfig.builder().setMaxJsonChars(0).build().getMaxJsonChars());

    StringBuilder input = new StringBuilder();
    for (int i = 0; i < 1000; i++) {
      input.append("long input ");
    }
    Attributes attrs =
        callChatCompletions(
            capture(GenAICaptureOptions.builder().captureInputMessages(true)), input.toString());

    // Longer than the 4,096 characters the wrapper used to allow.
    String messages = attrs.get(INPUT_MESSAGES);
    assertTrue(messages.length() > 10_000);
    assertTrue(!messages.endsWith("…(truncated)"));
  }

  @Test
  public void customAttributesAreAddedAndTheWrapperAttributesWin() {
    Map<String, Object> custom = new LinkedHashMap<String, Object>();
    custom.put("app.team", "support");
    custom.put("app.shard", 3);
    custom.put("app.beta", true);
    custom.put("app.regions", Arrays.asList("us", "eu"));
    custom.put("gen_ai.request.model", "overridden");
    OpenAIWrapperConfig config = OpenAIWrapperConfig.builder().setCustomAttributes(custom).build();

    Attributes attrs = callChatCompletions(config, "Hello!");

    assertEquals("support", attrs.get(AttributeKey.stringKey("app.team")));
    assertEquals(Long.valueOf(3), attrs.get(AttributeKey.longKey("app.shard")));
    assertEquals(Boolean.TRUE, attrs.get(AttributeKey.booleanKey("app.beta")));
    assertEquals("[\"us\",\"eu\"]", attrs.get(AttributeKey.stringKey("app.regions")));
    assertEquals("gpt-4o-mini", attrs.get(AttributeKey.stringKey("gen_ai.request.model")));
  }

  private static OpenAIWrapperConfig capture(GenAICaptureOptions.Builder options) {
    return OpenAIWrapperConfig.builder().setCaptureOptions(options.build()).build();
  }

  private Attributes callChatCompletions(OpenAIWrapperConfig config, String input) {
    return callWrapped(OpenAIWrapper.wrap(newClient(), config), input);
  }

  private Attributes callWrapped(OpenAIClient wrapped, String input) {
    wrapped.chat().completions().create(params(input));
    assertEquals(1, spanExporter.getFinishedSpanItems().size());
    SpanData span = spanExporter.getFinishedSpanItems().get(0);
    return span.getAttributes();
  }

  private static OpenAIClient newClient() {
    OpenAIClient client = mock(OpenAIClient.class);
    ChatService chatService = mock(ChatService.class);
    ChatCompletionService completions = mock(ChatCompletionService.class);
    when(client.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completions);
    when(completions.create(any(ChatCompletionCreateParams.class))).thenReturn(response());
    return client;
  }

  private static ChatCompletionCreateParams params(String input) {
    return ChatCompletionCreateParams.builder()
        .model("gpt-4o-mini")
        .addSystemMessage("You are helpful")
        .addUserMessage(input)
        .addFunctionTool(
            FunctionDefinition.builder()
                .name("search_docs")
                .description("Searches the docs")
                .build())
        .build();
  }

  private static ChatCompletion response() {
    return ChatCompletion.builder()
        .id("chatcmpl-1")
        .created(1L)
        .model("gpt-4o-mini")
        .addChoice(
            ChatCompletion.Choice.builder()
                .index(0)
                .finishReason(ChatCompletion.Choice.FinishReason.STOP)
                .logprobs(Optional.empty())
                .message(
                    ChatCompletionMessage.builder()
                        .content("Hi there")
                        .refusal(Optional.empty())
                        .build())
                .build())
        .usage(
            com.openai.models.completions.CompletionUsage.builder()
                .promptTokens(12)
                .completionTokens(3)
                .totalTokens(15)
                .build())
        .build();
  }

  private static void resetOtelSingleton() throws Exception {
    Field instanceField = OtelSingleton.class.getDeclaredField("instance");
    instanceField.setAccessible(true);
    instanceField.set(null, null);
  }
}
