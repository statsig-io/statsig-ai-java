package com.statsig.ai.instrumentation.openai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.ObjectMappers;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import com.statsig.StatsigUser;
import com.statsig.ai.instrumentation.GenAITelemetry;
import com.statsig.ai.trace.StatsigContextUtils;
import com.statsig.ai.trace.singleton.OtelSingleton;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class OpenAIWrapperTelemetryTest {

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
  public void wrapRequiresValidInputs() {
    assertThrows(NullPointerException.class, () -> OpenAIWrapper.wrap(null));
  }

  @Test
  public void chatCompletionsCreateEmitsSpan() {
    OpenAIClient client = mock(OpenAIClient.class);
    ChatService chatService = mock(ChatService.class);
    ChatCompletionService chatCompletionService = mock(ChatCompletionService.class);
    when(client.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(chatCompletionService);

    ChatCompletionCreateParams params = mock(ChatCompletionCreateParams.class);
    when(chatCompletionService.create(params)).thenReturn(null);

    OpenAIClient wrapped = OpenAIWrapper.wrap(client);
    wrapped.chat().completions().create(params);

    verify(chatCompletionService).create(params);
    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(1, spans.size());
    SpanData span = spans.get(0);
    // A mocked params object has no body, so no model.
    assertEquals("chat unknown", span.getName());
    assertEquals(SpanKind.CLIENT, span.getKind());
    assertEquals("chat", span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name")));
  }

  @Test
  public void chatCompletionsSpanCarriesStatsigContextAndEnvironment() {
    OpenAIClient client = mock(OpenAIClient.class);
    ChatService chatService = mock(ChatService.class);
    ChatCompletionService chatCompletionService = mock(ChatCompletionService.class);
    when(client.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(chatCompletionService);
    ChatCompletionCreateParams params = mock(ChatCompletionCreateParams.class);
    when(chatCompletionService.create(params)).thenReturn(null);
    OpenAIClient wrapped = OpenAIWrapper.wrap(client);

    StatsigUser user =
        new StatsigUser.Builder()
            .setUserID("wrapper-user")
            .setCustomIDs(Collections.singletonMap("org", "acme"))
            .build();
    StatsigContextUtils.withStatsigEnvironment(
        "eval",
        () ->
            StatsigContextUtils.withStatsigContext(
                user, "activity-1", () -> wrapped.chat().completions().create(params)));
    wrapped.chat().completions().create(params);

    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(2, spans.size());
    SpanData inside = spans.get(0);
    assertEquals(
        "wrapper-user", inside.getAttributes().get(AttributeKey.stringKey("statsig.user_id")));
    assertEquals(
        "{\"org\":\"acme\",\"statsig.activity_id\":\"activity-1\"}",
        inside.getAttributes().get(AttributeKey.stringKey("statsig.custom_ids")));
    assertEquals("eval", inside.getAttributes().get(AttributeKey.stringKey("statsig.environment")));

    SpanData outside = spans.get(1);
    assertNull(outside.getAttributes().get(AttributeKey.stringKey("statsig.user_id")));
    assertNull(outside.getAttributes().get(AttributeKey.stringKey("statsig.custom_ids")));
    assertNull(outside.getAttributes().get(AttributeKey.stringKey("statsig.environment")));
  }

  @Test
  public void recordsChatCompletionsToolCallsInTheOpenTelemetryFormatAndCallsTheApiOnce()
      throws Exception {
    JsonMapper mapper = ObjectMappers.jsonMapper();
    ChatCompletionCreateParams params =
        ChatCompletionCreateParams.builder()
            .body(
                mapper.readValue(
                    "{\"model\":\"gpt-test\",\"messages\":["
                        + "{\"role\":\"system\",\"content\":\"You are helpful.\"},"
                        + "{\"role\":\"user\",\"content\":\"Is checkout on?\"},"
                        + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
                        + "{\"id\":\"call_1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"list_gates\",\"arguments\":\"{}\"}}]},"
                        + "{\"role\":\"tool\",\"tool_call_id\":\"call_1\","
                        + "\"content\":\"[\\\"checkout\\\"]\"}]}",
                    ChatCompletionCreateParams.Body.class))
            .build();
    ChatCompletion response =
        mapper.readValue(
            "{\"id\":\"chatcmpl_1\",\"object\":\"chat.completion\",\"created\":1,"
                + "\"model\":\"gpt-test\",\"choices\":[{\"index\":0,\"logprobs\":null,"
                + "\"message\":{\"role\":\"assistant\",\"content\":null,\"refusal\":null,"
                + "\"tool_calls\":[{\"id\":\"call_2\",\"type\":\"function\","
                + "\"function\":{\"name\":\"get_gate\","
                + "\"arguments\":\"{\\\"id\\\":\\\"checkout\\\"}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}],"
                + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5,\"total_tokens\":17}}",
            ChatCompletion.class);
    OpenAIClient client = mock(OpenAIClient.class);
    ChatService chatService = mock(ChatService.class);
    ChatCompletionService completions = mock(ChatCompletionService.class);
    when(client.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completions);
    when(completions.create(params)).thenReturn(response);
    List<Map<String, String>> events = new ArrayList<Map<String, String>>();
    OpenAIClient wrapped =
        OpenAIWrapper.wrap(
            client,
            new GenAITelemetry(
                "test",
                OpenAIWrapperConfig.builder()
                    .setCaptureOptions(GenAICaptureOptions.builder().captureAll(true).build())
                    .build(),
                (user, name, metadata) -> events.add(metadata)));

    Context ctx =
        StatsigContextUtils.withPromptVersion(
            Context.current(), "support_agent", "v2", "version-2");
    ChatCompletion result;
    try (Scope ignored = ctx.makeCurrent()) {
      result = wrapped.chat().completions().create(params);
    }

    assertSame(response, result);
    verify(completions, times(1)).create(params);
    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(1, spans.size());
    SpanData span = spans.get(0);
    assertEquals("chat gpt-test", span.getName());
    Attributes attrs = span.getAttributes();
    assertEquals("chat", attrs.get(AttributeKey.stringKey("gen_ai.operation.name")));
    assertEquals(
        "openai.chat.completions.create",
        attrs.get(AttributeKey.stringKey("gen_ai.operation.source_name")));
    assertEquals("openai", attrs.get(AttributeKey.stringKey("gen_ai.provider.name")));
    assertEquals("version-2", attrs.get(AttributeKey.stringKey("statsig.llm.prompt_version_id")));
    assertEquals(
        mapper.readTree("[{\"type\":\"text\",\"content\":\"You are helpful.\"}]"),
        mapper.readTree(attrs.get(AttributeKey.stringKey("gen_ai.system_instructions"))));
    assertEquals(
        mapper.readTree(
            "[{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"content\":\"Is checkout on?\"}]},"
                + "{\"role\":\"assistant\",\"parts\":[{\"type\":\"tool_call\",\"id\":\"call_1\","
                + "\"name\":\"list_gates\",\"arguments\":{}}]},"
                + "{\"role\":\"tool\",\"parts\":[{\"type\":\"tool_call_response\","
                + "\"id\":\"call_1\",\"response\":\"[\\\"checkout\\\"]\"}]}]"),
        mapper.readTree(attrs.get(AttributeKey.stringKey("gen_ai.input.messages"))));
    assertEquals(
        mapper.readTree(
            "[{\"role\":\"assistant\",\"parts\":[{\"type\":\"tool_call\",\"id\":\"call_2\","
                + "\"name\":\"get_gate\",\"arguments\":{\"id\":\"checkout\"}}],"
                + "\"finish_reason\":\"tool_call\"}]"),
        mapper.readTree(attrs.get(AttributeKey.stringKey("gen_ai.output.messages"))));
    assertEquals(
        "[\"tool_calls\"]", attrs.get(AttributeKey.stringKey("gen_ai.response.finish_reasons")));
    assertEquals("chatcmpl_1", attrs.get(AttributeKey.stringKey("gen_ai.response.id")));
    assertEquals(Long.valueOf(12), attrs.get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
    assertEquals(Long.valueOf(5), attrs.get(AttributeKey.longKey("gen_ai.usage.output_tokens")));
    assertEquals(
        Long.valueOf(17), attrs.get(AttributeKey.longKey("statsig.gen_ai.usage.total_tokens")));
    String input = attrs.get(AttributeKey.stringKey("gen_ai.input.messages"));
    assertEquals(
        Long.valueOf(input.length()), attrs.get(AttributeKey.longKey("gen_ai.input.messages_len")));
    assertNotNull(attrs.get(AttributeKey.longKey("statsig.gen_ai.server.time_to_first_token_ms")));

    assertEquals(1, events.size());
    Map<String, String> metadata = events.get(0);
    assertEquals("chat gpt-test", metadata.get("span.name"));
    assertEquals(input, metadata.get("gen_ai.input.messages"));
    assertEquals("17", metadata.get("statsig.gen_ai.usage.total_tokens"));
    assertNotNull(metadata.get("duration"));
  }

  @Test
  public void wrapReturnsProxy() {
    OpenAIClient client = mock(OpenAIClient.class);
    OpenAIClient wrapped = OpenAIWrapper.wrap(client);
    assertNotNull(wrapped);
  }

  private static void resetOtelSingleton() throws Exception {
    Field instanceField = OtelSingleton.class.getDeclaredField("instance");
    instanceField.setAccessible(true);
    instanceField.set(null, null);
  }
}
