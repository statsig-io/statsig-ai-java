package com.statsig.ai.instrumentation.gemini;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.AsyncModels;
import com.google.genai.Client;
import com.google.genai.Models;
import com.google.genai.ResponseStream;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Ports the Node SDK's {@code wrapGemini} tests. */
public class GeminiWrapperTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private InMemorySpanExporter spanExporter;
  private SdkTracerProvider tracerProvider;
  private List<Map<String, String>> events;
  private Models models;
  private AsyncModels asyncModels;

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
    models = mock(Models.class);
    asyncModels = mock(AsyncModels.class);
  }

  @After
  public void tearDown() throws Exception {
    tracerProvider.shutdown().join(5, TimeUnit.SECONDS);
    resetOtelSingleton();
  }

  private static final String FUNCTION_CALL_RESPONSE =
      "{'responseId':'resp_1','modelVersion':'gemini-test-001','candidates':[{'content':"
          + "{'role':'model','parts':[{'functionCall':{'name':'get_gate',"
          + "'args':{'id':'checkout'}}}]},'finishReason':'STOP'}],"
          + "'usageMetadata':{'promptTokenCount':9,'candidatesTokenCount':3,"
          + "'thoughtsTokenCount':2}}";

  @Test
  public void recordsAFunctionCallingResponseInTheOpenTelemetryFormat() throws Exception {
    GenerateContentResponse response =
        GenerateContentResponse.fromJson(json(FUNCTION_CALL_RESPONSE));
    when(models.generateContent(
            eq("gemini-test"), anyList(), nullable(GenerateContentConfig.class)))
        .thenReturn(response);
    List<Content> contents =
        Arrays.asList(
            content("{'role':'user','parts':[{'text':'Is checkout on?'}]}"),
            content("{'role':'model','parts':[{'functionCall':{'name':'list_gates','args':{}}}]}"),
            content(
                "{'role':'user','parts':[{'functionResponse':{'name':'list_gates',"
                    + "'response':{'gates':['checkout']}}}]}"));
    GenerateContentConfig config =
        GenerateContentConfig.fromJson(
            json("{'systemInstruction':{'parts':[{'text':'Be brief.'}]},'temperature':0.1}"));

    Context ctx =
        StatsigContextUtils.withPromptVersion(
            Context.current(), "support_agent", "v2", "version-2");
    GenerateContentResponse result;
    try (Scope ignored = ctx.makeCurrent()) {
      result = wrapped(captureAll()).generateContent("gemini-test", contents, config);
    }

    assertSame(response, result);
    SpanData span = onlySpan();
    assertEquals("generate_content gemini-test", span.getName());
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    Attributes attrs = span.getAttributes();
    assertEquals("gcp.gen_ai", attrs.get(AttributeKey.stringKey("gen_ai.provider.name")));
    assertEquals("generate_content", attrs.get(AttributeKey.stringKey("gen_ai.operation.name")));
    assertEquals(
        "google.models.generate_content",
        attrs.get(AttributeKey.stringKey("gen_ai.operation.source_name")));
    assertEquals(0.1, attrs.get(AttributeKey.doubleKey("gen_ai.request.temperature")), 1e-6);
    assertEquals("gemini-test-001", attrs.get(AttributeKey.stringKey("gen_ai.response.model")));
    assertEquals(Long.valueOf(9), attrs.get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
    // Thinking tokens count toward output tokens.
    assertEquals(Long.valueOf(5), attrs.get(AttributeKey.longKey("gen_ai.usage.output_tokens")));
    assertEquals(
        Long.valueOf(2),
        attrs.get(AttributeKey.longKey("statsig.gen_ai.usage.output_reasoning_tokens")));
    assertEquals("version-2", attrs.get(AttributeKey.stringKey("statsig.llm.prompt_version_id")));
    assertJson(
        "[{'type':'text','content':'Be brief.'}]",
        attrs.get(AttributeKey.stringKey("gen_ai.system_instructions")));
    assertJson(
        "[{'role':'user','parts':[{'type':'text','content':'Is checkout on?'}]},"
            + "{'role':'assistant','parts':[{'type':'tool_call','id':'list_gates',"
            + "'name':'list_gates','arguments':{}}]},"
            + "{'role':'tool','parts':[{'type':'tool_call_response','id':'list_gates',"
            + "'response':{'gates':['checkout']}}]}]",
        attrs.get(AttributeKey.stringKey("gen_ai.input.messages")));
    assertJson(
        "[{'role':'assistant','parts':[{'type':'tool_call','id':'get_gate','name':'get_gate',"
            + "'arguments':{'id':'checkout'}}],'finish_reason':'tool_call'}]",
        attrs.get(AttributeKey.stringKey("gen_ai.output.messages")));
    assertEquals("generate_content gemini-test", events.get(0).get("span.name"));
    assertEquals("5", events.get(0).get("gen_ai.usage.output_tokens"));
  }

  @Test
  public void cachedInputTokensComeFromCachedContentTokenCount() throws Exception {
    when(models.generateContent(anyString(), anyString(), nullable(GenerateContentConfig.class)))
        .thenReturn(
            GenerateContentResponse.fromJson(
                json(
                    "{'candidates':[{'content':{'role':'model','parts':[{'text':'Hi'}]},"
                        + "'finishReason':'STOP'}],'usageMetadata':{'promptTokenCount':20,"
                        + "'candidatesTokenCount':1,'cachedContentTokenCount':16}}")));

    wrapped(captureAll()).generateContent("gemini-test", "Hi", null);

    Attributes attrs = onlySpan().getAttributes();
    assertEquals(
        Long.valueOf(16),
        attrs.get(AttributeKey.longKey("statsig.gen_ai.usage.input_cached_tokens")));
    assertEquals(
        Long.valueOf(21), attrs.get(AttributeKey.longKey("statsig.gen_ai.usage.total_tokens")));
    assertEquals("[\"STOP\"]", attrs.get(AttributeKey.stringKey("gen_ai.response.finish_reasons")));
  }

  @Test
  public void messageContentIsCapturedOnlyOnOptIn() throws Exception {
    when(models.generateContent(anyString(), anyString(), nullable(GenerateContentConfig.class)))
        .thenReturn(GenerateContentResponse.fromJson(json(FUNCTION_CALL_RESPONSE)));

    wrapped(null).generateContent("gemini-test", "Is checkout on?", null);

    Attributes attrs = onlySpan().getAttributes();
    assertNull(attrs.get(AttributeKey.stringKey("gen_ai.input.messages")));
    assertNull(attrs.get(AttributeKey.stringKey("gen_ai.output.messages")));
    assertEquals("resp_1", attrs.get(AttributeKey.stringKey("gen_ai.response.id")));
  }

  private static final String[] STREAM_CHUNKS = {
    "{'responseId':'resp_2','modelVersion':'gemini-test-001','candidates':[{'content':"
        + "{'role':'model','parts':[{'text':'Checkout '}]}}]}",
    "{'candidates':[{'content':{'role':'model','parts':[{'text':'is on.'}]},"
        + "'finishReason':'STOP'}],'usageMetadata':{'promptTokenCount':4,"
        + "'candidatesTokenCount':3}}"
  };

  private static final String STREAM_OUTPUT_MESSAGES =
      "[{'role':'assistant','parts':[{'type':'text','content':'Checkout is on.'}],"
          + "'finish_reason':'stop'}]";

  @Test
  public void mergesStreamedChunksIntoOneOutputMessage() throws Exception {
    ResponseStream<GenerateContentResponse> stream = streamOf(STREAM_CHUNKS);
    when(models.generateContentStream(
            eq("gemini-test"), eq("Is checkout on?"), nullable(GenerateContentConfig.class)))
        .thenReturn(stream);

    try (GeminiResponseStream<GenerateContentResponse> response =
        wrapped(captureAll()).generateContentStream("gemini-test", "Is checkout on?", null)) {
      assertTrue(spanExporter.getFinishedSpanItems().isEmpty());
      int count = 0;
      for (GenerateContentResponse ignored : response) {
        count++;
      }
      assertEquals(2, count);
    }

    SpanData span = onlySpan();
    Attributes attrs = span.getAttributes();
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    assertEquals(
        "google.models.generate_content_stream",
        attrs.get(AttributeKey.stringKey("gen_ai.operation.source_name")));
    assertEquals(Boolean.TRUE, attrs.get(AttributeKey.booleanKey("gen_ai.request.stream")));
    assertEquals("resp_2", attrs.get(AttributeKey.stringKey("gen_ai.response.id")));
    assertEquals(Long.valueOf(4), attrs.get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
    assertEquals(Long.valueOf(3), attrs.get(AttributeKey.longKey("gen_ai.usage.output_tokens")));
    assertNotNull(attrs.get(AttributeKey.longKey("statsig.gen_ai.server.time_to_first_token_ms")));
    assertJson(
        "[{'role':'user','parts':[{'type':'text','content':'Is checkout on?'}]}]",
        attrs.get(AttributeKey.stringKey("gen_ai.input.messages")));
    assertJson(STREAM_OUTPUT_MESSAGES, attrs.get(AttributeKey.stringKey("gen_ai.output.messages")));
  }

  @Test
  public void marksTheSpanAsFailedWhenTheCallThrows() throws Exception {
    IllegalStateException error = new IllegalStateException("quota exceeded");
    when(models.generateContent(anyString(), anyString(), nullable(GenerateContentConfig.class)))
        .thenThrow(error);

    try {
      wrapped(null).generateContent("gemini-test", "Hi", null);
      fail("expected the call's exception");
    } catch (IllegalStateException e) {
      assertSame(error, e);
    }

    SpanData span = onlySpan();
    assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
    assertEquals("quota exceeded", span.getStatus().getDescription());
  }

  @Test
  public void asyncCallsEndTheSpanWhenTheirFutureCompletes() throws Exception {
    CompletableFuture<GenerateContentResponse> future =
        new CompletableFuture<GenerateContentResponse>();
    when(asyncModels.generateContent(
            anyString(), anyString(), nullable(GenerateContentConfig.class)))
        .thenReturn(future);
    CompletableFuture<ResponseStream<GenerateContentResponse>> streamFuture =
        CompletableFuture.completedFuture(streamOf(STREAM_CHUNKS));
    when(asyncModels.generateContentStream(
            anyString(), anyString(), nullable(GenerateContentConfig.class)))
        .thenReturn(streamFuture);
    AsyncGeminiModels async = wrapped(captureAll()).async();

    CompletableFuture<GenerateContentResponse> result =
        async.generateContent("gemini-test", "Hi", null);
    assertTrue(spanExporter.getFinishedSpanItems().isEmpty());
    future.complete(GenerateContentResponse.fromJson(json(FUNCTION_CALL_RESPONSE)));
    result.join();
    assertEquals(
        "resp_1",
        spanExporter
            .getFinishedSpanItems()
            .get(0)
            .getAttributes()
            .get(AttributeKey.stringKey("gen_ai.response.id")));

    try (GeminiResponseStream<GenerateContentResponse> stream =
        async.generateContentStream("gemini-test", "Hi", null).join()) {
      stream.forEach(chunk -> {});
    }
    assertJson(
        STREAM_OUTPUT_MESSAGES,
        spanExporter
            .getFinishedSpanItems()
            .get(1)
            .getAttributes()
            .get(AttributeKey.stringKey("gen_ai.output.messages")));

    CompletableFuture<GenerateContentResponse> failing =
        new CompletableFuture<GenerateContentResponse>();
    when(asyncModels.generateContent(
            anyString(), anyString(), nullable(GenerateContentConfig.class)))
        .thenReturn(failing);
    CompletableFuture<GenerateContentResponse> failed =
        async.generateContent("gemini-test", "Hi", null);
    failing.completeExceptionally(new IllegalStateException("quota exceeded"));
    assertThrows(CompletionException.class, failed::join);
    assertEquals(
        StatusCode.ERROR, spanExporter.getFinishedSpanItems().get(2).getStatus().getStatusCode());
  }

  @Test
  public void wrapUsesTheClientsModels() {
    Client client = Client.builder().apiKey("test-key").build();
    try {
      GeminiModels wrapped = GeminiWrapper.wrap(client);
      assertSame(client.models, wrapped.getModels());
      assertSame(client.async.models, wrapped.async().getModels());
    } finally {
      client.close();
    }
    assertThrows(NullPointerException.class, () -> GeminiWrapper.wrap(null));
  }

  // ----------------------------------------------------------------------
  // Helpers
  // ----------------------------------------------------------------------

  private GeminiModels wrapped(OpenAIWrapperConfig config) {
    return new GeminiModels(
        models,
        asyncModels,
        new GenAITelemetry("test", config, (user, name, metadata) -> events.add(metadata)));
  }

  private static OpenAIWrapperConfig captureAll() {
    return OpenAIWrapperConfig.builder()
        .setCaptureOptions(GenAICaptureOptions.builder().captureAll(true).build())
        .build();
  }

  @SuppressWarnings("unchecked")
  private static ResponseStream<GenerateContentResponse> streamOf(String... chunks) {
    List<GenerateContentResponse> responses = new ArrayList<GenerateContentResponse>();
    for (String chunk : chunks) {
      responses.add(GenerateContentResponse.fromJson(json(chunk)));
    }
    ResponseStream<GenerateContentResponse> stream = mock(ResponseStream.class);
    when(stream.iterator()).thenReturn(responses.iterator());
    return stream;
  }

  private static Content content(String json) {
    return Content.fromJson(json(json));
  }

  // Fixtures use single quotes for readability.
  private static String json(String singleQuoted) {
    return singleQuoted.replace('\'', '"');
  }

  private static void assertJson(String expected, String actual) throws Exception {
    JsonNode expectedNode = MAPPER.readTree(json(expected));
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
}
