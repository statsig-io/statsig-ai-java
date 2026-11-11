package com.statsig.ai.instrumentation.openai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openai.client.OpenAIClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import com.statsig.StatsigUser;
import com.statsig.ai.trace.StatsigContextUtils;
import com.statsig.ai.trace.singleton.OtelSingleton;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
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
    assertEquals("openai.chat.completions", span.getName());
    assertEquals(SpanKind.CLIENT, span.getKind());
    assertEquals(
        "chat.completions",
        span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name")));
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
