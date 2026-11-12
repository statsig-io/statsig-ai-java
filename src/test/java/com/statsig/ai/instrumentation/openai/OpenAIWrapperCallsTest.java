package com.statsig.ai.instrumentation.openai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.core.ObjectMappers;
import com.openai.core.http.AsyncStreamResponse;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.completions.Completion;
import com.openai.models.completions.CompletionCreateParams;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.models.images.ImageGenerateParams;
import com.openai.models.images.ImagesResponse;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.StructuredResponse;
import com.openai.models.responses.StructuredResponseCreateParams;
import com.openai.services.async.ChatServiceAsync;
import com.openai.services.async.chat.ChatCompletionServiceAsync;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.CompletionService;
import com.openai.services.blocking.EmbeddingService;
import com.openai.services.blocking.ImageService;
import com.openai.services.blocking.ModelService;
import com.openai.services.blocking.ResponseService;
import com.openai.services.blocking.chat.ChatCompletionService;
import com.statsig.ai.instrumentation.GenAITelemetry;
import com.statsig.ai.trace.singleton.OtelSingleton;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Spliterators;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Ports the Node SDK's OpenAI wrapper tests for the calls besides blocking chat completions. */
public class OpenAIWrapperCallsTest {
  private static final JsonMapper MAPPER = ObjectMappers.jsonMapper();

  private InMemorySpanExporter spanExporter;
  private SdkTracerProvider tracerProvider;
  private List<Map<String, String>> events;
  private GenAITelemetry telemetry;

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
    telemetry =
        new GenAITelemetry(
            "test",
            OpenAIWrapperConfig.builder()
                .setCaptureOptions(GenAICaptureOptions.builder().captureAll(true).build())
                .build(),
            (user, name, metadata) -> events.add(metadata));
  }

  @After
  public void tearDown() throws Exception {
    tracerProvider.shutdown().join(5, TimeUnit.SECONDS);
    resetOtelSingleton();
  }

  // ----------------------------------------------------------------------
  // Streaming chat completions
  // ----------------------------------------------------------------------

  private static final String CHAT_STREAM_REQUEST =
      "{'model':'gpt-test','messages':[{'role':'user','content':'Is checkout on?'}]}";

  private static final String[] CHAT_STREAM_CHUNKS = {
    "{'id':'chatcmpl_2','model':'gpt-test','choices':[{'index':0,"
        + "'delta':{'role':'assistant','content':'Let me check. '}}]}",
    "{'choices':[{'index':0,'delta':{'tool_calls':[{'index':0,'id':'call_3',"
        + "'function':{'name':'get_gate','arguments':'{\\\"id\\\":'}}]}}]}",
    "{'choices':[{'index':0,'delta':{'tool_calls':[{'index':0,"
        + "'function':{'arguments':'\\\"checkout\\\"}'}}]},'finish_reason':'tool_calls'}],"
        + "'usage':{'prompt_tokens':9,'completion_tokens':4}}"
  };

  @Test
  public void streamingChatAggregatesTextAndToolCallDeltasAndCallsTheApiOnce() throws Exception {
    ChatCompletionService completions = mock(ChatCompletionService.class);
    FakeStream<ChatCompletionChunk> stream =
        new FakeStream<ChatCompletionChunk>(chunks(ChatCompletionChunk.class, CHAT_STREAM_CHUNKS));
    when(completions.createStreaming(any(ChatCompletionCreateParams.class))).thenReturn(stream);
    OpenAIClient wrapped = OpenAIWrapper.wrap(clientWithChat(completions), telemetry);

    try (StreamResponse<ChatCompletionChunk> response =
        wrapped.chat().completions().createStreaming(chatParams(CHAT_STREAM_REQUEST))) {
      assertTrue("the span ends after the stream is read", spans().isEmpty());
      assertEquals(3, response.stream().count());
    }

    verify(completions, times(1)).createStreaming(any(ChatCompletionCreateParams.class));
    assertTrue(stream.closed);
    SpanData span = onlySpan();
    Attributes attrs = span.getAttributes();
    assertEquals("chat gpt-test", span.getName());
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    assertEquals(Boolean.TRUE, attrs.get(AttributeKey.booleanKey("gen_ai.request.stream")));
    assertEquals("chatcmpl_2", attrs.get(AttributeKey.stringKey("gen_ai.response.id")));
    assertEquals(
        "[\"tool_calls\"]", attrs.get(AttributeKey.stringKey("gen_ai.response.finish_reasons")));
    assertEquals(
        Long.valueOf(13), attrs.get(AttributeKey.longKey("statsig.gen_ai.usage.total_tokens")));
    assertNotNull(attrs.get(AttributeKey.longKey("statsig.gen_ai.server.time_to_first_token_ms")));
    assertJson(
        "[{'role':'assistant','parts':[{'type':'text','content':'Let me check. '},"
            + "{'type':'tool_call','id':'call_3','name':'get_gate','arguments':{'id':'checkout'}}],"
            + "'finish_reason':'tool_call'}]",
        attrs.get(AttributeKey.stringKey("gen_ai.output.messages")));
    assertEquals("true", events.get(0).get("gen_ai.request.stream"));
  }

  @Test
  public void streamClosedBeforeTheEndEndsTheSpanWithoutAResponse() throws Exception {
    ChatCompletionService completions = mock(ChatCompletionService.class);
    when(completions.createStreaming(any(ChatCompletionCreateParams.class)))
        .thenReturn(
            new FakeStream<ChatCompletionChunk>(
                chunks(ChatCompletionChunk.class, CHAT_STREAM_CHUNKS)));
    OpenAIClient wrapped = OpenAIWrapper.wrap(clientWithChat(completions), telemetry);

    try (StreamResponse<ChatCompletionChunk> response =
        wrapped.chat().completions().createStreaming(chatParams(CHAT_STREAM_REQUEST))) {
      Iterator<ChatCompletionChunk> chunks = response.stream().iterator();
      chunks.next();
    }

    SpanData span = onlySpan();
    assertEquals(StatusCode.UNSET, span.getStatus().getStatusCode());
    assertNull(span.getAttributes().get(AttributeKey.stringKey("gen_ai.output.messages")));
    assertNotNull(
        span.getAttributes()
            .get(AttributeKey.longKey("statsig.gen_ai.server.time_to_first_token_ms")));
  }

  @Test
  public void streamThatFailsMarksTheSpanAsFailed() throws Exception {
    ChatCompletionService completions = mock(ChatCompletionService.class);
    FakeStream<ChatCompletionChunk> stream =
        new FakeStream<ChatCompletionChunk>(chunks(ChatCompletionChunk.class, CHAT_STREAM_CHUNKS));
    stream.failAfter = 1;
    when(completions.createStreaming(any(ChatCompletionCreateParams.class))).thenReturn(stream);
    OpenAIClient wrapped = OpenAIWrapper.wrap(clientWithChat(completions), telemetry);

    try (StreamResponse<ChatCompletionChunk> response =
        wrapped.chat().completions().createStreaming(chatParams(CHAT_STREAM_REQUEST))) {
      response.stream().forEach(chunk -> {});
      fail("expected the stream's exception");
    } catch (IllegalStateException e) {
      assertEquals("connection reset", e.getMessage());
    }

    SpanData span = onlySpan();
    assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
    assertEquals("connection reset", span.getStatus().getDescription());
  }

  // ----------------------------------------------------------------------
  // Async client
  // ----------------------------------------------------------------------

  @Test
  public void asyncChatCompletionEndsTheSpanWhenTheFutureCompletes() throws Exception {
    ChatCompletionServiceAsync completions = mock(ChatCompletionServiceAsync.class);
    CompletableFuture<ChatCompletion> future = new CompletableFuture<ChatCompletion>();
    when(completions.create(any(ChatCompletionCreateParams.class))).thenReturn(future);
    OpenAIClientAsync wrapped = OpenAIWrapper.wrap(asyncClientWithChat(completions), telemetry);

    CompletableFuture<ChatCompletion> result =
        wrapped.chat().completions().create(chatParams(CHAT_STREAM_REQUEST));
    assertTrue(spans().isEmpty());
    ChatCompletion completion =
        parse(
            ChatCompletion.class,
            "{'id':'chatcmpl_9','model':'gpt-test','choices':[{'index':0,"
                + "'message':{'role':'assistant','content':'Yes.'},'finish_reason':'stop'}]}");
    future.complete(completion);

    assertSame(completion, result.join());
    SpanData span = onlySpan();
    assertEquals("chat gpt-test", span.getName());
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    assertJson(
        "[{'role':'assistant','parts':[{'type':'text','content':'Yes.'}],'finish_reason':'stop'}]",
        span.getAttributes().get(AttributeKey.stringKey("gen_ai.output.messages")));
  }

  @Test
  public void failedAsyncCallMarksTheSpanAsFailed() throws Exception {
    ChatCompletionServiceAsync completions = mock(ChatCompletionServiceAsync.class);
    CompletableFuture<ChatCompletion> future = new CompletableFuture<ChatCompletion>();
    when(completions.create(any(ChatCompletionCreateParams.class))).thenReturn(future);
    OpenAIClientAsync wrapped = OpenAIWrapper.wrap(asyncClientWithChat(completions), telemetry);

    CompletableFuture<ChatCompletion> result =
        wrapped.chat().completions().create(chatParams(CHAT_STREAM_REQUEST));
    IllegalStateException error = new IllegalStateException("rate limited");
    future.completeExceptionally(error);

    try {
      result.join();
      fail("expected the call's exception");
    } catch (CompletionException e) {
      assertSame(error, e.getCause());
    }
    SpanData span = onlySpan();
    assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
    assertEquals("rate limited", span.getStatus().getDescription());
  }

  @Test
  public void asyncStreamEndsTheSpanWhenItCompletes() throws Exception {
    ChatCompletionServiceAsync completions = mock(ChatCompletionServiceAsync.class);
    when(completions.createStreaming(any(ChatCompletionCreateParams.class)))
        .thenReturn(
            new FakeAsyncStream<ChatCompletionChunk>(
                chunks(ChatCompletionChunk.class, CHAT_STREAM_CHUNKS)));
    OpenAIClientAsync wrapped = OpenAIWrapper.wrap(asyncClientWithChat(completions), telemetry);
    List<ChatCompletionChunk> received = new ArrayList<ChatCompletionChunk>();

    AsyncStreamResponse<ChatCompletionChunk> response =
        wrapped.chat().completions().createStreaming(chatParams(CHAT_STREAM_REQUEST));
    response.subscribe(received::add);
    response.onCompleteFuture().join();

    assertEquals(3, received.size());
    SpanData span = onlySpan();
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    assertEquals(
        Boolean.TRUE, span.getAttributes().get(AttributeKey.booleanKey("gen_ai.request.stream")));
    assertJson(
        "[{'role':'assistant','parts':[{'type':'text','content':'Let me check. '},"
            + "{'type':'tool_call','id':'call_3','name':'get_gate','arguments':{'id':'checkout'}}],"
            + "'finish_reason':'tool_call'}]",
        span.getAttributes().get(AttributeKey.stringKey("gen_ai.output.messages")));
  }

  @Test
  public void asyncAndSyncReturnWrappedClients() {
    OpenAIClient client = mock(OpenAIClient.class);
    OpenAIClientAsync async = mock(OpenAIClientAsync.class);
    when(client.async()).thenReturn(async);
    when(async.sync()).thenReturn(client);
    ModelService models = mock(ModelService.class);
    when(client.models()).thenReturn(models);

    OpenAIClient wrapped = OpenAIWrapper.wrap(client, telemetry);

    assertTrue(Proxy.isProxyClass(wrapped.async().getClass()));
    assertTrue(Proxy.isProxyClass(wrapped.async().sync().getClass()));
    // Services without traced calls are passed through.
    assertSame(models, wrapped.models());
  }

  // ----------------------------------------------------------------------
  // Completions, embeddings and images
  // ----------------------------------------------------------------------

  @Test
  public void completionsAreTracedAsTextCompletion() throws Exception {
    CompletionService service = mock(CompletionService.class);
    when(service.create(any(CompletionCreateParams.class)))
        .thenReturn(
            parse(
                Completion.class,
                "{'id':'cmpl_1','model':'gpt-3.5-turbo-instruct','choices':[{'index':0,"
                    + "'text':'Hi!','finish_reason':'stop','logprobs':null}],"
                    + "'usage':{'prompt_tokens':3,'completion_tokens':2,'total_tokens':5}}"));
    OpenAIClient client = mock(OpenAIClient.class);
    when(client.completions()).thenReturn(service);

    OpenAIWrapper.wrap(client, telemetry)
        .completions()
        .create(
            CompletionCreateParams.builder()
                .body(
                    parse(
                        CompletionCreateParams.Body.class,
                        "{'model':'gpt-3.5-turbo-instruct','prompt':'Say hi'}"))
                .build());

    SpanData span = onlySpan();
    Attributes attrs = span.getAttributes();
    assertEquals("text_completion gpt-3.5-turbo-instruct", span.getName());
    assertEquals("text_completion", attrs.get(AttributeKey.stringKey("gen_ai.operation.name")));
    assertEquals(
        "openai.completions.create",
        attrs.get(AttributeKey.stringKey("gen_ai.operation.source_name")));
    assertJson(
        "[{'role':'user','parts':[{'type':'text','content':'Say hi'}]}]",
        attrs.get(AttributeKey.stringKey("gen_ai.input.messages")));
    assertJson(
        "[{'role':'assistant','parts':[{'type':'text','content':'Hi!'}],'finish_reason':'stop'}]",
        attrs.get(AttributeKey.stringKey("gen_ai.output.messages")));
  }

  @Test
  public void embeddingsRecordTheDimensionsAndEncodingFormat() throws Exception {
    EmbeddingService service = mock(EmbeddingService.class);
    when(service.create(any(EmbeddingCreateParams.class)))
        .thenReturn(
            parse(
                CreateEmbeddingResponse.class,
                "{'object':'list','model':'text-embedding-3-small',"
                    + "'data':[{'object':'embedding','index':0,'embedding':[0.1,0.2]}],"
                    + "'usage':{'prompt_tokens':2,'total_tokens':2}}"));
    OpenAIClient client = mock(OpenAIClient.class);
    when(client.embeddings()).thenReturn(service);

    OpenAIWrapper.wrap(client, telemetry)
        .embeddings()
        .create(
            EmbeddingCreateParams.builder()
                .body(
                    parse(
                        EmbeddingCreateParams.Body.class,
                        "{'model':'text-embedding-3-small','input':'hello','dimensions':256,"
                            + "'encoding_format':'float'}"))
                .build());

    SpanData span = onlySpan();
    Attributes attrs = span.getAttributes();
    assertEquals("embeddings text-embedding-3-small", span.getName());
    assertEquals("embeddings", attrs.get(AttributeKey.stringKey("gen_ai.operation.name")));
    assertEquals(
        Long.valueOf(256), attrs.get(AttributeKey.longKey("gen_ai.embeddings.dimension.count")));
    assertEquals(
        "[\"float\"]", attrs.get(AttributeKey.stringKey("gen_ai.request.encoding_formats")));
    assertEquals(Long.valueOf(2), attrs.get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
    assertNull(attrs.get(AttributeKey.stringKey("gen_ai.output.messages")));
    assertEquals("embeddings", events.get(0).get("gen_ai.operation.name"));
  }

  @Test
  public void imageGenerationRecordsTheImageSettings() throws Exception {
    ImageService service = mock(ImageService.class);
    when(service.generate(any(ImageGenerateParams.class)))
        .thenReturn(
            parse(ImagesResponse.class, "{'created':1,'data':[{'url':'https://x/cat.png'}]}"));
    OpenAIClient client = mock(OpenAIClient.class);
    when(client.images()).thenReturn(service);

    OpenAIWrapper.wrap(client, telemetry)
        .images()
        .generate(
            ImageGenerateParams.builder()
                .body(
                    parse(
                        ImageGenerateParams.Body.class,
                        "{'model':'gpt-image-1','prompt':'a cat','size':'1024x1024',"
                            + "'quality':'high','output_format':'png','output_compression':80}"))
                .build());

    SpanData span = onlySpan();
    Attributes attrs = span.getAttributes();
    assertEquals("generate_content gpt-image-1", span.getName());
    assertEquals(
        "openai.images.generate",
        attrs.get(AttributeKey.stringKey("gen_ai.operation.source_name")));
    assertEquals("1024x1024", attrs.get(AttributeKey.stringKey("statsig.gen_ai.images.size")));
    assertEquals("high", attrs.get(AttributeKey.stringKey("statsig.gen_ai.images.quality")));
    assertEquals("png", attrs.get(AttributeKey.stringKey("statsig.gen_ai.images.output_format")));
    assertEquals(
        Long.valueOf(80),
        attrs.get(AttributeKey.longKey("statsig.gen_ai.images.output_compression")));
  }

  @Test
  public void failedCallMarksTheSpanAsFailedAndRethrows() throws Exception {
    EmbeddingService service = mock(EmbeddingService.class);
    IllegalStateException error = new IllegalStateException("bad request");
    when(service.create(any(EmbeddingCreateParams.class))).thenThrow(error);
    OpenAIClient client = mock(OpenAIClient.class);
    when(client.embeddings()).thenReturn(service);

    try {
      OpenAIWrapper.wrap(client, telemetry)
          .embeddings()
          .create(
              EmbeddingCreateParams.builder()
                  .body(
                      parse(
                          EmbeddingCreateParams.Body.class,
                          "{'model':'text-embedding-3-small','input':'hello'}"))
                  .build());
      fail("expected the call's exception");
    } catch (IllegalStateException e) {
      assertSame(error, e);
    }

    SpanData span = onlySpan();
    assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
    assertEquals("ERROR", events.get(0).get("span.status_code"));
  }

  // ----------------------------------------------------------------------
  // Responses
  // ----------------------------------------------------------------------

  private static final String RESPONSES_REQUEST =
      "{'model':'gpt-test','instructions':'Be brief.','input':["
          + "{'role':'user','content':[{'type':'input_text','text':'Is checkout on?'}]},"
          + "{'type':'function_call','call_id':'call_1','name':'list_gates','arguments':'{}'},"
          + "{'type':'function_call_output','call_id':'call_1','output':'[\\\"checkout\\\"]'}]}";

  private static final String RESPONSES_RESPONSE =
      "{'id':'resp_1','model':'gpt-test','status':'completed','output':["
          + "{'type':'reasoning','id':'rs_1','summary':[{'type':'summary_text',"
          + "'text':'Gate is listed.'}]},"
          + "{'type':'message','id':'msg_1','role':'assistant','status':'completed',"
          + "'content':[{'type':'output_text','text':'Checkout is on.','annotations':[]}]}],"
          + "'usage':{'input_tokens':20,'output_tokens':8,'total_tokens':28}}";

  private static final String RESPONSES_OUTPUT_MESSAGES =
      "[{'role':'assistant','parts':[{'type':'reasoning','content':'Gate is listed.'},"
          + "{'type':'text','content':'Checkout is on.'}],'finish_reason':'stop'}]";

  @Test
  public void responsesRecordInstructionsInputItemsAndOutput() throws Exception {
    ResponseService service = mock(ResponseService.class);
    when(service.create(any(ResponseCreateParams.class)))
        .thenReturn(parse(Response.class, RESPONSES_RESPONSE));
    OpenAIClient client = mock(OpenAIClient.class);
    when(client.responses()).thenReturn(service);

    OpenAIWrapper.wrap(client, telemetry).responses().create(responsesParams());

    SpanData span = onlySpan();
    Attributes attrs = span.getAttributes();
    assertEquals("generate_content gpt-test", span.getName());
    assertEquals(
        "openai.responses.create",
        attrs.get(AttributeKey.stringKey("gen_ai.operation.source_name")));
    assertJson(
        "[{'type':'text','content':'Be brief.'}]",
        attrs.get(AttributeKey.stringKey("gen_ai.system_instructions")));
    assertJson(
        "[{'role':'user','parts':[{'type':'text','content':'Is checkout on?'}]},"
            + "{'role':'assistant','parts':[{'type':'tool_call','id':'call_1',"
            + "'name':'list_gates','arguments':{}}]},"
            + "{'role':'tool','parts':[{'type':'tool_call_response','id':'call_1',"
            + "'response':'[\\\"checkout\\\"]'}]}]",
        attrs.get(AttributeKey.stringKey("gen_ai.input.messages")));
    assertJson(
        RESPONSES_OUTPUT_MESSAGES, attrs.get(AttributeKey.stringKey("gen_ai.output.messages")));
    assertEquals(
        Long.valueOf(28), attrs.get(AttributeKey.longKey("statsig.gen_ai.usage.total_tokens")));
  }

  @Test
  public void responsesStreamIsReadFromItsCompletedEvent() throws Exception {
    ResponseService service = mock(ResponseService.class);
    String completed = RESPONSES_RESPONSE;
    when(service.createStreaming(any(ResponseCreateParams.class)))
        .thenReturn(
            new FakeStream<ResponseStreamEvent>(
                chunks(
                    ResponseStreamEvent.class,
                    "{'type':'response.created','sequence_number':0,'response':{'id':'resp_1',"
                        + "'model':'gpt-test','status':'in_progress','output':[]}}",
                    "{'type':'response.output_text.delta','sequence_number':1,'item_id':'msg_1',"
                        + "'output_index':1,'content_index':0,'delta':'Checkout is on.'}",
                    "{'type':'response.completed','sequence_number':2,'response':"
                        + completed
                        + "}")));
    OpenAIClient client = mock(OpenAIClient.class);
    when(client.responses()).thenReturn(service);

    try (StreamResponse<ResponseStreamEvent> stream =
        OpenAIWrapper.wrap(client, telemetry).responses().createStreaming(responsesParams())) {
      stream.stream().forEach(event -> {});
    }

    SpanData span = onlySpan();
    Attributes attrs = span.getAttributes();
    assertEquals(Boolean.TRUE, attrs.get(AttributeKey.booleanKey("gen_ai.request.stream")));
    assertEquals("resp_1", attrs.get(AttributeKey.stringKey("gen_ai.response.id")));
    assertEquals(Long.valueOf(20), attrs.get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
    assertJson(
        RESPONSES_OUTPUT_MESSAGES, attrs.get(AttributeKey.stringKey("gen_ai.output.messages")));
  }

  @Test
  public void structuredResponsesAreTracedAsParse() throws Exception {
    ResponseService service = mock(ResponseService.class);
    @SuppressWarnings("unchecked")
    StructuredResponseCreateParams<Object> params = mock(StructuredResponseCreateParams.class);
    when(params.rawParams()).thenReturn(responsesParams());
    @SuppressWarnings("unchecked")
    StructuredResponse<Object> structured = mock(StructuredResponse.class);
    when(structured.rawResponse()).thenReturn(parse(Response.class, RESPONSES_RESPONSE));
    when(service.create(params)).thenReturn(structured);
    OpenAIClient client = mock(OpenAIClient.class);
    when(client.responses()).thenReturn(service);

    assertSame(structured, OpenAIWrapper.wrap(client, telemetry).responses().create(params));

    Attributes attrs = onlySpan().getAttributes();
    assertEquals(
        "openai.responses.parse",
        attrs.get(AttributeKey.stringKey("gen_ai.operation.source_name")));
    assertEquals("generate_content", attrs.get(AttributeKey.stringKey("gen_ai.operation.name")));
    assertEquals("resp_1", attrs.get(AttributeKey.stringKey("gen_ai.response.id")));
  }

  // ----------------------------------------------------------------------
  // Helpers
  // ----------------------------------------------------------------------

  private static OpenAIClient clientWithChat(ChatCompletionService completions) {
    OpenAIClient client = mock(OpenAIClient.class);
    ChatService chat = mock(ChatService.class);
    when(client.chat()).thenReturn(chat);
    when(chat.completions()).thenReturn(completions);
    return client;
  }

  private static OpenAIClientAsync asyncClientWithChat(ChatCompletionServiceAsync completions) {
    OpenAIClientAsync client = mock(OpenAIClientAsync.class);
    ChatServiceAsync chat = mock(ChatServiceAsync.class);
    when(client.chat()).thenReturn(chat);
    when(chat.completions()).thenReturn(completions);
    return client;
  }

  private static ChatCompletionCreateParams chatParams(String json) throws Exception {
    return ChatCompletionCreateParams.builder()
        .body(parse(ChatCompletionCreateParams.Body.class, json))
        .build();
  }

  private static ResponseCreateParams responsesParams() throws Exception {
    return ResponseCreateParams.builder()
        .body(parse(ResponseCreateParams.Body.class, RESPONSES_REQUEST))
        .build();
  }

  // Fixtures use single quotes for readability.
  private static <T> T parse(Class<T> type, String json) throws Exception {
    return MAPPER.readValue(json.replace('\'', '"'), type);
  }

  private static <T> List<T> chunks(Class<T> type, String... json) throws Exception {
    List<T> chunks = new ArrayList<T>();
    for (String chunk : json) {
      chunks.add(parse(type, chunk));
    }
    return chunks;
  }

  private static void assertJson(String expected, String actual) throws Exception {
    JsonNode expectedNode = MAPPER.readTree(expected.replace('\'', '"'));
    assertNotNull("missing attribute, expected " + expectedNode, actual);
    assertEquals(expectedNode, MAPPER.readTree(actual));
  }

  private List<SpanData> spans() {
    return spanExporter.getFinishedSpanItems();
  }

  private SpanData onlySpan() {
    List<SpanData> spans = spans();
    assertEquals(1, spans.size());
    return spans.get(0);
  }

  private static void resetOtelSingleton() throws Exception {
    Field instanceField = OtelSingleton.class.getDeclaredField("instance");
    instanceField.setAccessible(true);
    instanceField.set(null, null);
  }

  /** A blocking stream over a list. It throws after {@code failAfter} items when that's set. */
  private static final class FakeStream<T> implements StreamResponse<T> {
    private final List<T> items;
    int failAfter = -1;
    boolean closed = false;

    FakeStream(List<T> items) {
      this.items = items;
    }

    @Override
    public Stream<T> stream() {
      if (failAfter < 0) {
        return items.stream();
      }
      Iterator<T> source = items.iterator();
      Iterator<T> failing =
          new Iterator<T>() {
            private int read = 0;

            @Override
            public boolean hasNext() {
              return source.hasNext();
            }

            @Override
            public T next() {
              if (read++ == failAfter) {
                throw new IllegalStateException("connection reset");
              }
              return source.next();
            }
          };
      return StreamSupport.stream(Spliterators.spliteratorUnknownSize(failing, 0), false);
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  /** An async stream over a list, which sends every item when it's subscribed to. */
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
