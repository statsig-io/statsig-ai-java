package com.statsig.ai;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.openai.client.OpenAIClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import com.statsig.DynamicConfig;
import com.statsig.EvaluationDetails;
import com.statsig.StatsigUser;
import com.statsig.ai.instrumentation.openai.GenAICaptureOptions;
import com.statsig.ai.instrumentation.openai.OpenAIWrapper;
import com.statsig.ai.instrumentation.openai.OpenAIWrapperConfig;
import com.statsig.ai.trace.StatsigContextUtils;
import com.statsig.ai.trace.StatsigSpanProcessor;
import com.statsig.ai.trace.StatsigTraceConventions;
import com.statsig.ai.trace.UserContext;
import com.statsig.ai.trace.singleton.OtelSingleton;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

public class PromptContextTest {
  // From prompt.json.
  private static final String PROMPT_NAME = "test_prompt";
  private static final String LIVE_VERSION_NAME = "Version 2";
  private static final String LIVE_VERSION_ID = "7jszgFEAi1KRA2Tot6qikg";

  private SdkTracerProvider tracerProvider;

  @After
  public void tearDown() throws Exception {
    if (tracerProvider != null) {
      tracerProvider.shutdown().join(5, TimeUnit.SECONDS);
      resetOtelSingleton();
    }
  }

  @Test
  public void withLiveSetsPromptAndUserContext() throws Exception {
    Prompt prompt = buildPrompt("prompt-user");
    PromptVersion live = prompt.getLive();

    prompt.withLive(
        () -> {
          Context ctx = Context.current();
          assertEquals(prompt.getName(), ctx.get(StatsigTraceConventions.CTX_ACTIVE_PROMPT));
          assertEquals(live.getName(), ctx.get(StatsigTraceConventions.CTX_ACTIVE_PROMPT_VERSION));
          assertEquals(live.getId(), ctx.get(StatsigTraceConventions.CTX_ACTIVE_PROMPT_VERSION_ID));

          UserContext userCtx = StatsigContextUtils.getActiveUser(ctx);
          assertNotNull(userCtx);
          assertEquals("prompt-user", userCtx.getUserID());
          assertEquals(Collections.singletonMap("cohort", "beta"), userCtx.getCustomIDs());
        });

    assertNull(StatsigContextUtils.getActiveUser(Context.current()));
  }

  @Test
  public void withLiveTagsTheLiveVersionInsideOnly() throws Exception {
    Prompt prompt = buildPrompt("prompt-user");
    assertTrue(StatsigContextUtils.getPromptSpanAttrs(Context.current()).isEmpty());

    String result =
        prompt.withLive(
            () -> {
              assertEquals(
                  promptAttrs(PROMPT_NAME, LIVE_VERSION_NAME, LIVE_VERSION_ID),
                  StatsigContextUtils.getPromptSpanAttrs(Context.current()));
              return "live-result";
            });

    assertEquals("live-result", result);
    assertTrue(StatsigContextUtils.getPromptSpanAttrs(Context.current()).isEmpty());
  }

  @Test
  public void withVersionTagsACandidate() throws Exception {
    PromptVersion candidate = version("prompt:candidate-b", "Candidate B");
    Prompt prompt = buildPrompt("prompt-user", candidate);

    String result =
        prompt.withVersion(
            candidate,
            () -> {
              assertEquals(
                  promptAttrs(PROMPT_NAME, "Candidate B", "candidate-b"),
                  StatsigContextUtils.getPromptSpanAttrs(Context.current()));
              assertEquals(
                  "prompt-user", StatsigContextUtils.getActiveUser(Context.current()).getUserID());
              return "candidate-result";
            });

    assertEquals("candidate-result", result);
    assertTrue(StatsigContextUtils.getPromptSpanAttrs(Context.current()).isEmpty());
    assertNull(StatsigContextUtils.getActiveUser(Context.current()));
  }

  @Test
  public void withVersionRunsARunnable() throws Exception {
    PromptVersion candidate = version("prompt:candidate-b", "Candidate B");
    Prompt prompt = buildPrompt("prompt-user", candidate);
    AtomicReference<Map<String, String>> attrs = new AtomicReference<Map<String, String>>();

    prompt.withVersion(
        candidate, () -> attrs.set(StatsigContextUtils.getPromptSpanAttrs(Context.current())));

    assertEquals(promptAttrs(PROMPT_NAME, "Candidate B", "candidate-b"), attrs.get());
  }

  @Test
  public void valueReturningExpressionLambdasPickTheSupplierOverload() throws Exception {
    ChatCompletionCreateParams params = mock(ChatCompletionCreateParams.class);
    ChatCompletion completion = mock(ChatCompletion.class);
    OpenAIClient client = mockOpenAIClient(params, completion);
    PromptVersion candidate = version("prompt:candidate-b", "Candidate B");
    Prompt prompt = buildPrompt("prompt-user", candidate);

    // These only compile if the expression lambda resolves to Supplier rather than Runnable.
    ChatCompletion live = prompt.withLive(() -> client.chat().completions().create(params));
    ChatCompletion candidateCompletion =
        prompt.withVersion(candidate, () -> client.chat().completions().create(params));

    assertSame(completion, live);
    assertSame(completion, candidateCompletion);
  }

  @Test
  public void innerVersionReplacesTheOuterOne() throws Exception {
    PromptVersion candidate = version("prompt:candidate-b", "Candidate B");
    // A config name without a colon has no version ID.
    PromptVersion noID = version("draft", "Draft");
    Prompt prompt = buildPrompt("prompt-user", candidate, noID);

    prompt.withLive(
        () -> {
          prompt.withVersion(
              candidate,
              () ->
                  assertEquals(
                      promptAttrs(PROMPT_NAME, "Candidate B", "candidate-b"),
                      StatsigContextUtils.getPromptSpanAttrs(Context.current())));
          // The inner version doesn't inherit the live version's ID.
          prompt.withVersion(
              noID,
              () -> {
                Map<String, String> expected = new HashMap<String, String>();
                expected.put(StatsigTraceConventions.ATTR_LLM_PROMPT_NAME, PROMPT_NAME);
                expected.put(StatsigTraceConventions.ATTR_LLM_PROMPT_VERSION, "Draft");
                assertEquals(expected, StatsigContextUtils.getPromptSpanAttrs(Context.current()));
              });
          assertEquals(
              promptAttrs(PROMPT_NAME, LIVE_VERSION_NAME, LIVE_VERSION_ID),
              StatsigContextUtils.getPromptSpanAttrs(Context.current()));
        });
  }

  @Test
  public void withContextSetsPromptNameAndUserWithoutAVersion() throws Exception {
    Prompt prompt = buildPrompt("prompt-user-2");

    String result =
        prompt.withContext(
            () -> {
              Context ctx = Context.current();
              assertEquals(prompt.getName(), ctx.get(StatsigTraceConventions.CTX_ACTIVE_PROMPT));
              assertNull(ctx.get(StatsigTraceConventions.CTX_ACTIVE_PROMPT_VERSION));
              assertNull(ctx.get(StatsigTraceConventions.CTX_ACTIVE_PROMPT_VERSION_ID));
              assertEquals(
                  Collections.singletonMap(
                      StatsigTraceConventions.ATTR_LLM_PROMPT_NAME, PROMPT_NAME),
                  StatsigContextUtils.getPromptSpanAttrs(ctx));

              UserContext userCtx = StatsigContextUtils.getActiveUser(ctx);
              assertNotNull(userCtx);
              assertEquals("prompt-user-2", userCtx.getUserID());
              assertEquals(Collections.singletonMap("cohort", "beta"), userCtx.getCustomIDs());
              return "context-result";
            });

    assertEquals("context-result", result);
    assertTrue(StatsigContextUtils.getPromptSpanAttrs(Context.current()).isEmpty());
    assertNull(StatsigContextUtils.getActiveUser(Context.current()));
  }

  @Test
  public void withContextKeepsTheOuterVersion() throws Exception {
    PromptVersion candidate = version("prompt:candidate-b", "Candidate B");
    Prompt prompt = buildPrompt("prompt-user", candidate);
    Prompt other =
        new Prompt("other_prompt", prompt.getLive(), new PromptVersion[0], prompt.getUser());

    prompt.withVersion(
        candidate,
        () ->
            other.withContext(
                () ->
                    assertEquals(
                        promptAttrs("other_prompt", "Candidate B", "candidate-b"),
                        StatsigContextUtils.getPromptSpanAttrs(Context.current()))));
  }

  @Test
  public void modelSpansInsideWithVersionGetThePromptVersionAndUser() throws Exception {
    InMemorySpanExporter exporter = InMemorySpanExporter.create();
    tracerProvider =
        SdkTracerProvider.builder()
            .addSpanProcessor(new StatsigSpanProcessor(SimpleSpanProcessor.create(exporter)))
            .build();
    resetOtelSingleton();
    OtelSingleton.instantiate(tracerProvider);

    ChatCompletionCreateParams params = mock(ChatCompletionCreateParams.class);
    OpenAIClient wrapped = OpenAIWrapper.wrap(mockOpenAIClient(params, null));

    PromptVersion candidate = version("prompt:candidate-b", "Candidate B");
    Prompt prompt = buildPrompt("prompt-user", candidate);
    prompt.withVersion(candidate, () -> wrapped.chat().completions().create(params));
    wrapped.chat().completions().create(params);

    List<SpanData> spans = exporter.getFinishedSpanItems();
    assertEquals(2, spans.size());
    Attributes inside = spans.get(0).getAttributes();
    assertEquals(PROMPT_NAME, inside.get(AttributeKey.stringKey("statsig.llm.prompt_name")));
    assertEquals("Candidate B", inside.get(AttributeKey.stringKey("statsig.llm.prompt_version")));
    assertEquals(
        "candidate-b", inside.get(AttributeKey.stringKey("statsig.llm.prompt_version_id")));
    // Stamped by StatsigSpanProcessor from the prompt's user.
    assertEquals("prompt-user", inside.get(AttributeKey.stringKey("statsig.user_id")));
    assertEquals("{\"cohort\":\"beta\"}", inside.get(AttributeKey.stringKey("statsig.custom_ids")));

    Attributes outside = spans.get(1).getAttributes();
    assertNull(outside.get(AttributeKey.stringKey("statsig.llm.prompt_name")));
    assertNull(outside.get(AttributeKey.stringKey("statsig.llm.prompt_version")));
    assertNull(outside.get(AttributeKey.stringKey("statsig.llm.prompt_version_id")));
    assertNull(outside.get(AttributeKey.stringKey("statsig.user_id")));
  }

  @Test
  public void withVersionRecordsTheTemplateVariablesItsVersionRenders() throws Exception {
    PromptVersion candidate = version("prompt:candidate-b", "Candidate B");
    PromptVersion other = version("prompt:candidate-c", "Candidate C");
    Prompt prompt = buildPrompt("prompt-user", candidate, other);

    prompt.withVersion(
        candidate,
        () -> {
          assertFalse(
              StatsigContextUtils.getPromptSpanAttrs(Context.current(), true)
                  .containsKey(StatsigTraceConventions.ATTR_LLM_PROMPT_TEMPLATE_VARIABLES));

          candidate.getPromptMessages(Collections.singletonMap("input", "hello"));
          // Rendering a version the scope isn't for leaves the variables alone.
          other.getPromptMessages(Collections.singletonMap("input", "ignored"));

          assertEquals(
              Collections.singletonMap("input", "hello"),
              StatsigContextUtils.getPromptSpanAttrs(Context.current(), true)
                  .get(StatsigTraceConventions.ATTR_LLM_PROMPT_TEMPLATE_VARIABLES));
          assertFalse(
              StatsigContextUtils.getPromptSpanAttrs(Context.current(), false)
                  .containsKey(StatsigTraceConventions.ATTR_LLM_PROMPT_TEMPLATE_VARIABLES));
        });
  }

  @Test
  public void getPromptMessagesOutsideAVersionScopeRecordsNothing() throws Exception {
    PromptVersion live = buildPrompt("prompt-user").getLive();

    assertEquals(
        Collections.singletonList(new PromptMessage("system", "hoiiiiii hi")),
        live.getPromptMessages(Collections.singletonMap("input", "hi")));
    assertTrue(StatsigContextUtils.getPromptSpanAttrs(Context.current(), true).isEmpty());
  }

  @Test
  public void modelSpansGetTheTemplateVariablesOnlyWhenInputMessagesAreCaptured() throws Exception {
    InMemorySpanExporter exporter = InMemorySpanExporter.create();
    tracerProvider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    resetOtelSingleton();
    OtelSingleton.instantiate(tracerProvider);

    ChatCompletionCreateParams params = mock(ChatCompletionCreateParams.class);
    OpenAIClient capturing =
        OpenAIWrapper.wrap(
            mockOpenAIClient(params, null),
            OpenAIWrapperConfig.builder()
                .setCaptureOptions(GenAICaptureOptions.builder().captureInputMessages(true).build())
                .build());
    OpenAIClient notCapturing = OpenAIWrapper.wrap(mockOpenAIClient(params, null));

    Prompt prompt = buildPrompt("prompt-user");
    prompt.withLive(
        () -> {
          prompt.getLive().getPromptMessages(Collections.singletonMap("input", "Hi"));
          capturing.chat().completions().create(params);
          notCapturing.chat().completions().create(params);
        });

    List<SpanData> spans = exporter.getFinishedSpanItems();
    assertEquals(2, spans.size());
    AttributeKey<String> variables =
        AttributeKey.stringKey(StatsigTraceConventions.ATTR_LLM_PROMPT_TEMPLATE_VARIABLES);
    assertEquals("{\"input\":\"Hi\"}", spans.get(0).getAttributes().get(variables));
    assertNull(spans.get(1).getAttributes().get(variables));
    assertEquals(
        LIVE_VERSION_ID,
        spans.get(1).getAttributes().get(AttributeKey.stringKey("statsig.llm.prompt_version_id")));
  }

  private static OpenAIClient mockOpenAIClient(
      ChatCompletionCreateParams params, ChatCompletion result) {
    OpenAIClient client = mock(OpenAIClient.class);
    ChatService chatService = mock(ChatService.class);
    ChatCompletionService chatCompletionService = mock(ChatCompletionService.class);
    when(client.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(chatCompletionService);
    when(chatCompletionService.create(params)).thenReturn(result);
    return client;
  }

  private static Map<String, String> promptAttrs(
      String promptName, String versionName, String versionID) {
    Map<String, String> attrs = new HashMap<String, String>();
    attrs.put(StatsigTraceConventions.ATTR_LLM_PROMPT_NAME, promptName);
    attrs.put(StatsigTraceConventions.ATTR_LLM_PROMPT_VERSION, versionName);
    attrs.put(StatsigTraceConventions.ATTR_LLM_PROMPT_VERSION_ID, versionID);
    return attrs;
  }

  private static Prompt buildPrompt(String userId, PromptVersion... candidates) throws Exception {
    JSONObject payload = JSON.parseObject(TestUtils.readResource("/prompt.json"));
    PromptVersion config = new PromptVersion(toDynamicConfig(payload), true);
    StatsigUser user =
        new StatsigUser.Builder()
            .setUserID(userId)
            .setCustomIDs(Collections.singletonMap("cohort", "beta"))
            .build();
    return new Prompt(config.getPromptName(), config, candidates, user);
  }

  private static PromptVersion version(String configName, String versionName) throws Exception {
    Map<String, Object> value = new HashMap<String, Object>();
    value.put("name", versionName);
    value.put("aiConfigName", PROMPT_NAME);
    return new PromptVersion(newDynamicConfig(configName, value, "userID"), false);
  }

  private static DynamicConfig toDynamicConfig(JSONObject payload) throws Exception {
    String key = payload.getString("key");
    String idType = payload.getString("idType");

    JSONObject defaultObject = payload.getJSONObject("defaultValue");
    Map<String, Object> defaultValue = new HashMap<>(defaultObject);

    DynamicConfig config = newDynamicConfig(key, defaultValue, idType);
    config.setRawJson(payload.toJSONString());
    return config;
  }

  private static DynamicConfig newDynamicConfig(
      String key, Map<String, Object> defaultValue, String idType) throws Exception {
    Constructor<DynamicConfig> ctor =
        DynamicConfig.class.getDeclaredConstructor(
            String.class, Map.class, String.class, EvaluationDetails.class, String.class);
    ctor.setAccessible(true);
    return ctor.newInstance(key, defaultValue, "", evaluationDetails(), idType);
  }

  private static EvaluationDetails evaluationDetails() throws Exception {
    Constructor<EvaluationDetails> ctor =
        EvaluationDetails.class.getDeclaredConstructor(long.class, long.class, String.class);
    ctor.setAccessible(true);
    return ctor.newInstance(0L, 0L, "unit_test");
  }

  private static void resetOtelSingleton() throws Exception {
    Field instanceField = OtelSingleton.class.getDeclaredField("instance");
    instanceField.setAccessible(true);
    instanceField.set(null, null);
  }
}
