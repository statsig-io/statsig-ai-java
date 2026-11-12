package com.statsig.ai.instrumentation.anthropic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientAsync;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.http.AsyncStreamResponse;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.services.async.MessageServiceAsync;
import com.anthropic.services.blocking.MessageService;
import com.anthropic.services.blocking.ModelService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.statsig.ai.instrumentation.GenAITelemetry;
import com.statsig.ai.instrumentation.openai.GenAICaptureOptions;
import com.statsig.ai.instrumentation.openai.OpenAIWrapperConfig;
import com.statsig.ai.trace.StatsigContextUtils;
import com.statsig.ai.trace.singleton.OtelSingleton;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Ports the Node SDK's {@code wrapAnthropic} tests. */
public class AnthropicWrapperTest {
  private static final JsonMapper MAPPER = ObjectMappers.jsonMapper();

  private InMemorySpanExporter spanExporter;
  private SdkTracerProvider tracerProvider;
  private List<Map<String, String>> events;

  @Before
  public void setUp() throws Exception {
    spanExporter = InMemorySpanExporter.create();
    tracerProvider =
        SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
            .build();
    resetOtelSingleton();
    OtelSingleton.instantiate(tracerProvider);
    events = new ArrayList<Map<String, String>>();
  }

  @After
  public void tearDown() throws Exception {
    tracerProvider.shutdown().join(5, TimeUnit.SECONDS);
    resetOtelSingleton();
  }

  private static final String TOOL_TURN_REQUEST =
      "{'model':'claude-test','max_tokens':256,'temperature':0.2,'system':'You are helpful.',"
          + "'tools':[{'name':'get_gate','input_schema':{'type':'object'}}],'messages':["
          + "{'role':'user','content':'Is checkout on?'},"
          + "{'role':'assistant','content':[{'type':'tool_use','id':'toolu_1',"
          + "'name':'list_gates','input':{}}]},"
          + "{'role':'user','content':[{'type':'tool_result','tool_use_id':'toolu_1',"
          + "'content':[{'type':'text','text':'[\\\"checkout\\\"]'}]}]}]}";

  private static final String TOOL_TURN_RESPONSE =
      "{'id':'msg_1','type':'message','model':'claude-test','role':'assistant','content':["
          + "{'type':'thinking','thinking':'Check the gate.','signature':'sig'},"
          + "{'type':'tool_use','id':'toolu_2','name':'get_gate','input':{'id':'checkout'}}],"
          + "'stop_reason':'tool_use','stop_sequence':null,"
          + "'usage':{'input_tokens':10,'cache_read_input_tokens':4,'output_tokens':6}}";

  @Test
  public void recordsAToolUsingTurnInTheOpenTelemetryFormatWithPromptAttributes() throws Exception {
    MessageService messages = mock(MessageService.class);
    Message response = parse(Message.class, TOOL_TURN_RESPONSE);
    when(messages.create(any(MessageCreateParams.class))).thenReturn(response);
    AnthropicClient client = wrapWithEvents(clientWith(messages), captureAll());

    Context ctx =
        StatsigContextUtils.withPromptVersion(
            Context.current(), "support_agent", "v2", "version-2");
    Message result;
    try (Scope ignored = ctx.makeCurrent()) {
      result = client.messages().create(params(TOOL_TURN_REQUEST));
    }

    assertSame(response, result);
    verify(messages, times(1)).create(any(MessageCreateParams.class));
    SpanData span = onlySpan();
    assertEquals("chat claude-test", span.getName());
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    Attributes attrs = span.getAttributes();
    assertEquals("anthropic", attrs.get(AttributeKey.stringKey("gen_ai.provider.name")));
    assertEquals("chat", attrs.get(AttributeKey.stringKey("gen_ai.operation.name")));
    assertEquals(
        "anthropic.messages.create",
        attrs.get(AttributeKey.stringKey("gen_ai.operation.source_name")));
    assertEquals("claude-test", attrs.get(AttributeKey.stringKey("gen_ai.request.model")));
    assertEquals(Long.valueOf(256), attrs.get(AttributeKey.longKey("gen_ai.request.max_tokens")));
    assertEquals(0.2, attrs.get(AttributeKey.doubleKey("gen_ai.request.temperature")), 0.0);
    assertEquals("msg_1", attrs.get(AttributeKey.stringKey("gen_ai.response.id")));
    // Cache reads count toward input tokens.
    assertEquals(Long.valueOf(14), attrs.get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
    assertEquals(Long.valueOf(6), attrs.get(AttributeKey.longKey("gen_ai.usage.output_tokens")));
    assertEquals(
        Long.valueOf(4),
        attrs.get(AttributeKey.longKey("statsig.gen_ai.usage.input_cached_tokens")));
    assertEquals("version-2", attrs.get(AttributeKey.stringKey("statsig.llm.prompt_version_id")));
    assertEquals(
        "[\"tool_use\"]", attrs.get(AttributeKey.stringKey("gen_ai.response.finish_reasons")));
    assertJson(
        "[{'type':'text','content':'You are helpful.'}]",
        attrs.get(AttributeKey.stringKey("gen_ai.system_instructions")));
    assertJson(
        "[{'role':'user','parts':[{'type':'text','content':'Is checkout on?'}]},"
            + "{'role':'assistant','parts':[{'type':'tool_call','id':'toolu_1',"
            + "'name':'list_gates','arguments':{}}]},"
            + "{'role':'tool','parts':[{'type':'tool_call_response','id':'toolu_1',"
            + "'response':'[\\\"checkout\\\"]'}]}]",
        attrs.get(AttributeKey.stringKey("gen_ai.input.messages")));
    assertJson(
        "[{'role':'assistant','parts':[{'type':'reasoning','content':'Check the gate.'},"
            + "{'type':'tool_call','id':'toolu_2','name':'get_gate',"
            + "'arguments':{'id':'checkout'}}],'finish_reason':'tool_call'}]",
        attrs.get(AttributeKey.stringKey("gen_ai.output.messages")));
    assertJson(
        "[{'name':'get_gate','input_schema':{'type':'object'}}]",
        attrs.get(AttributeKey.stringKey("gen_ai.tool.definitions")));
    assertEquals("chat claude-test", events.get(0).get("span.name"));
    assertEquals("14", events.get(0).get("gen_ai.usage.input_tokens"));
  }

  @Test
  public void messageContentIsCapturedOnlyOnOptIn() throws Exception {
    MessageService messages = mock(MessageService.class);
    when(messages.create(any(MessageCreateParams.class)))
        .thenReturn(parse(Message.class, TOOL_TURN_RESPONSE));
    AnthropicClient client = AnthropicWrapper.wrap(clientWith(messages));

    client.messages().create(params(TOOL_TURN_REQUEST));

    Attributes attrs = onlySpan().getAttributes();
    assertNull(attrs.get(AttributeKey.stringKey("gen_ai.input.messages")));
    assertNull(attrs.get(AttributeKey.stringKey("gen_ai.output.messages")));
    assertNull(attrs.get(AttributeKey.stringKey("gen_ai.system_instructions")));
    assertNull(attrs.get(AttributeKey.stringKey("gen_ai.tool.definitions")));
    assertEquals("msg_1", attrs.get(AttributeKey.stringKey("gen_ai.response.id")));
  }

  private static final String[] STREAM_EVENTS = {
    "{'type':'message_start','message':{'id':'msg_2','type':'message','role':'assistant',"
        + "'model':'claude-test','content':[],'stop_reason':null,'stop_sequence':null,"
        + "'usage':{'input_tokens':8,'output_tokens':0}}}",
    "{'type':'content_block_start','index':0,'content_block':{'type':'text','text':''}}",
    "{'type':'content_block_delta','index':0,'delta':{'type':'text_delta','text':'Checking '}}",
    "{'type':'content_block_delta','index':0,'delta':{'type':'text_delta','text':'now.'}}",
    "{'type':'content_block_start','index':1,'content_block':{'type':'tool_use',"
        + "'id':'toolu_3','name':'get_gate','input':{}}}",
    "{'type':'content_block_delta','index':1,'delta':{'type':'input_json_delta',"
        + "'partial_json':'{\\\"id\\\":'}}",
    "{'type':'content_block_delta','index':1,'delta':{'type':'input_json_delta',"
        + "'partial_json':'\\\"checkout\\\"}'}}",
    "{'type':'message_delta','delta':{'stop_reason':'tool_use','stop_sequence':null},"
        + "'usage':{'output_tokens':12}}",
    "{'type':'message_stop'}"
  };

  private static final String STREAM_OUTPUT_MESSAGES =
      "[{'role':'assistant','parts':[{'type':'text','content':'Checking now.'},"
          + "{'type':'tool_call','id':'toolu_3','name':'get_gate',"
          + "'arguments':{'id':'checkout'}}],'finish_reason':'tool_call'}]";

  @Test
  public void rebuildsAStreamedMessageAndEndsTheSpanAfterTheStreamIsRead() throws Exception {
    MessageService messages = mock(MessageService.class);
    FakeStream<RawMessageStreamEvent> stream =
        new FakeStream<RawMessageStreamEvent>(streamEvents());
    when(messages.createStreaming(any(MessageCreateParams.class))).thenReturn(stream);
    AnthropicClient client = AnthropicWrapper.wrap(clientWith(messages), captureAll());

    List<RawMessageStreamEvent> received = new ArrayList<RawMessageStreamEvent>();
    try (StreamResponse<RawMessageStreamEvent> response =
        client
            .messages()
            .createStreaming(
                params(
                    "{'model':'claude-test','max_tokens':64,"
                        + "'messages':[{'role':'user','content':'Is checkout on?'}]}"))) {
      assertTrue(
          "the span ends after the stream is read", spanExporter.getFinishedSpanItems().isEmpty());
      response.stream().forEach(received::add);
    }

    assertEquals(STREAM_EVENTS.length, received.size());
    assertTrue(stream.closed);
    SpanData span = onlySpan();
    Attributes attrs = span.getAttributes();
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    assertEquals(Boolean.TRUE, attrs.get(AttributeKey.booleanKey("gen_ai.request.stream")));
    assertEquals("msg_2", attrs.get(AttributeKey.stringKey("gen_ai.response.id")));
    assertEquals(Long.valueOf(8), attrs.get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
    assertEquals(Long.valueOf(12), attrs.get(AttributeKey.longKey("gen_ai.usage.output_tokens")));
    assertNotNull(attrs.get(AttributeKey.longKey("statsig.gen_ai.server.time_to_first_token_ms")));
    assertJson(STREAM_OUTPUT_MESSAGES, attrs.get(AttributeKey.stringKey("gen_ai.output.messages")));
  }

  @Test
  public void marksTheSpanAsFailedWhenTheCallThrows() throws Exception {
    MessageService messages = mock(MessageService.class);
    IllegalStateException error = new IllegalStateException("overloaded");
    when(messages.create(any(MessageCreateParams.class))).thenThrow(error);
    AnthropicClient client = AnthropicWrapper.wrap(clientWith(messages));

    try {
      client.messages().create(params("{'model':'claude-test','max_tokens':16,'messages':[]}"));
      fail("expected the call's exception");
    } catch (IllegalStateException e) {
      assertSame(error, e);
    }

    SpanData span = onlySpan();
    assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
    assertEquals("overloaded", span.getStatus().getDescription());
  }

  @Test
  public void asyncCreateEndsTheSpanWhenTheFutureCompletes() throws Exception {
    MessageServiceAsync messages = mock(MessageServiceAsync.class);
    CompletableFuture<Message> future = new CompletableFuture<Message>();
    when(messages.create(any(MessageCreateParams.class))).thenReturn(future);
    AnthropicClientAsync client =
        AnthropicWrapper.wrap(asyncClientWith(messages), telemetry(captureAll()));

    CompletableFuture<Message> result = client.messages().create(params(TOOL_TURN_REQUEST));
    assertTrue(spanExporter.getFinishedSpanItems().isEmpty());
    future.complete(parse(Message.class, TOOL_TURN_RESPONSE));
    result.join();

    SpanData span = onlySpan();
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    assertEquals("msg_1", span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.id")));

    CompletableFuture<Message> failing = new CompletableFuture<Message>();
    when(messages.create(any(MessageCreateParams.class))).thenReturn(failing);
    CompletableFuture<Message> failed = client.messages().create(params(TOOL_TURN_REQUEST));
    failing.completeExceptionally(new IllegalStateException("overloaded"));
    CompletionException thrown = assertThrows(CompletionException.class, failed::join);
    assertEquals("overloaded", thrown.getCause().getMessage());
    assertEquals(
        StatusCode.ERROR, spanExporter.getFinishedSpanItems().get(1).getStatus().getStatusCode());
  }

  @Test
  public void asyncStreamRebuildsTheMessage() throws Exception {
    MessageServiceAsync messages = mock(MessageServiceAsync.class);
    when(messages.createStreaming(any(MessageCreateParams.class)))
        .thenReturn(new FakeAsyncStream<RawMessageStreamEvent>(streamEvents()));
    AnthropicClientAsync client =
        AnthropicWrapper.wrap(asyncClientWith(messages), telemetry(captureAll()));

    AsyncStreamResponse<RawMessageStreamEvent> response =
        client
            .messages()
            .createStreaming(params("{'model':'claude-test','max_tokens':64,'messages':[]}"));
    response.subscribe(event -> {});
    response.onCompleteFuture().join();

    Attributes attrs = onlySpan().getAttributes();
    assertEquals(Boolean.TRUE, attrs.get(AttributeKey.booleanKey("gen_ai.request.stream")));
    assertJson(STREAM_OUTPUT_MESSAGES, attrs.get(AttributeKey.stringKey("gen_ai.output.messages")));
  }

  @Test
  public void asyncAndSyncReturnWrappedClientsAndOtherServicesPassThrough() {
    AnthropicClient client = mock(AnthropicClient.class);
    AnthropicClientAsync async = mock(AnthropicClientAsync.class);
    ModelService models = mock(ModelService.class);
    when(client.async()).thenReturn(async);
    when(async.sync()).thenReturn(client);
    when(client.models()).thenReturn(models);

    AnthropicClient wrapped = AnthropicWrapper.wrap(client);

    assertTrue(Proxy.isProxyClass(wrapped.async().getClass()));
    assertTrue(Proxy.isProxyClass(wrapped.async().sync().getClass()));
    assertSame(models, wrapped.models());
    assertThrows(NullPointerException.class, () -> AnthropicWrapper.wrap((AnthropicClient) null));
  }

  // ----------------------------------------------------------------------
  // Helpers
  // ----------------------------------------------------------------------

  private AnthropicClient clientWith(MessageService messages) {
    AnthropicClient client = mock(AnthropicClient.class);
    when(client.messages()).thenReturn(messages);
    return client;
  }

  private static AnthropicClientAsync asyncClientWith(MessageServiceAsync messages) {
    AnthropicClientAsync client = mock(AnthropicClientAsync.class);
    when(client.messages()).thenReturn(messages);
    return client;
  }

  private static OpenAIWrapperConfig captureAll() {
    return OpenAIWrapperConfig.builder()
        .setCaptureOptions(GenAICaptureOptions.builder().captureAll(true).build())
        .build();
  }

  private GenAITelemetry telemetry(OpenAIWrapperConfig config) {
    return new GenAITelemetry("test", config, (user, name, metadata) -> events.add(metadata));
  }

  private AnthropicClient wrapWithEvents(AnthropicClient client, OpenAIWrapperConfig config) {
    return AnthropicWrapper.wrap(client, telemetry(config));
  }

  private static MessageCreateParams params(String json) throws Exception {
    return MessageCreateParams.builder().body(parse(MessageCreateParams.Body.class, json)).build();
  }

  private static List<RawMessageStreamEvent> streamEvents() throws Exception {
    List<RawMessageStreamEvent> events = new ArrayList<RawMessageStreamEvent>();
    for (String event : STREAM_EVENTS) {
      events.add(parse(RawMessageStreamEvent.class, event));
    }
    return events;
  }

  // Fixtures use single quotes for readability.
  private static <T> T parse(Class<T> type, String json) throws Exception {
    return MAPPER.readValue(json.replace('\'', '"'), type);
  }

  private static void assertJson(String expected, String actual) throws Exception {
    JsonNode expectedNode = MAPPER.readTree(expected.replace('\'', '"'));
    assertNotNull("missing attribute, expected " + expectedNode, actual);
    assertEquals(expectedNode, MAPPER.readTree(actual));
  }

  private SpanData onlySpan() {
    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    assertEquals(1, spans.size());
    return spans.get(0);
  }

  private static void resetOtelSingleton() throws Exception {
    Field instanceField = OtelSingleton.class.getDeclaredField("instance");
    instanceField.setAccessible(true);
    instanceField.set(null, null);
  }

  private static final class FakeStream<T> implements StreamResponse<T> {
    private final List<T> items;
    boolean closed = false;

    FakeStream(List<T> items) {
      this.items = items;
    }

    @Override
    public Stream<T> stream() {
      return items.stream();
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  private static final class FakeAsyncStream<T> implements AsyncStreamResponse<T> {
    private final List<T> items;
    private final CompletableFuture<Void> completion = new CompletableFuture<Void>();

    FakeAsyncStream(List<T> items) {
      this.items = items;
    }

    @Override
    public AsyncStreamResponse<T> subscribe(Handler<? super T> handler) {
      for (T item : items) {
        handler.onNext(item);
      }
      handler.onComplete(Optional.<Throwable>empty());
      completion.complete(null);
      return this;
    }

    @Override
    public AsyncStreamResponse<T> subscribe(Handler<? super T> handler, Executor executor) {
      return subscribe(handler);
    }

    @Override
    public CompletableFuture<Void> onCompleteFuture() {
      return completion;
    }

    @Override
    public void close() {}
  }
}
