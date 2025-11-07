package com.statsig.ai.prompts;

import static org.junit.Assert.*;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.statsig.DynamicConfig;
import com.statsig.EvaluationDetails;
import com.statsig.StatsigUser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class PromptTest {

  @Test
  public void promptVariantIsHydratedFromSingleConfig() throws Exception {
    JSONObject payload = loadPromptConfig();
    PromptVariant variant = new PromptVariant(toDynamicConfig(payload));

    StatsigUser user = new StatsigUser();
    Prompt prompt = new Prompt(variant.getAiConfigName(), variant, new PromptVariant[0], user);

    assertEquals("test_prompt", prompt.getName());
    assertEquals("Version 2", prompt.getLivePrompt().getName());
    assertEquals("7jszgFEAi1KRA2Tot6qikg", prompt.getLivePrompt().getId());
    assertEquals(0, prompt.getCandidatePrompts().length);
    assertEquals("openai", prompt.getLivePrompt().getProvider());
    assertEquals("gpt-5", prompt.getLivePrompt().getModel());
    assertEquals("gpt-4o-mini", prompt.getLivePrompt().getEvalModel());
    assertEquals(1.0, prompt.getLivePrompt().getTemperature(), 0.0);
    assertEquals(1000, prompt.getLivePrompt().getMaxTokens());
    assertEquals("JSON", prompt.getLivePrompt().getWorkflowBody().get("type"));
    assertEquals("{}", prompt.getLivePrompt().getWorkflowBody().get("value"));
  }

  @Test
  public void fallbackGettersReturnProvidedDefaults() {
    DynamicConfig emptyConfig = newDynamicConfig("prompt:empty", new HashMap<>(), "userID");
    PromptVariant variant = new PromptVariant(emptyConfig);

    assertEquals(0.0, variant.getTemperature(), 0.0);
    assertEquals(0.75, variant.getTemperature(0.75), 0.0);
    assertEquals(0, variant.getMaxTokens());
    assertEquals(2048, variant.getMaxTokens(2048));
    assertEquals(0.0, variant.getTopP(), 0.0);
    assertEquals(0.9, variant.getTopP(0.9), 0.0);
    assertEquals(0.0, variant.getFrequencyPenalty(), 0.0);
    assertEquals(0.5, variant.getFrequencyPenalty(0.5), 0.0);
    assertEquals(0.0, variant.getPresencePenalty(), 0.0);
    assertEquals(0.25, variant.getPresencePenalty(0.25), 0.0);
    assertEquals("default-provider", variant.getProvider("default-provider"));
    assertEquals("default-model", variant.getModel("default-model"));

    Map<String, Object> workflowFallback = new HashMap<>();
    workflowFallback.put("type", "JSON");
    workflowFallback.put("value", "{}");
    assertNull(variant.getWorkflowBody());
    assertEquals(workflowFallback, variant.getWorkflowBody(workflowFallback));
    assertEquals("default-eval", variant.getEvalModel("default-eval"));
  }

  private static JSONObject loadPromptConfig() throws IOException {
    try (InputStream stream = PromptTest.class.getResourceAsStream("/prompt.json")) {
      assertNotNull("Fixture prompt.json should exist", stream);
      String json = toString(stream);
      return JSON.parseObject(json);
    }
  }

  private static DynamicConfig toDynamicConfig(JSONObject payload) {
    String key = payload.getString("key");
    String idType = payload.getString("idType");

    JSONObject defaultObject = payload.getJSONObject("defaultValue");
    Map<String, Object> defaultValue = new HashMap<>(defaultObject);

    DynamicConfig config = newDynamicConfig(key, defaultValue, idType);
    config.setRawJson(payload.toJSONString());
    return config;
  }

  private static EvaluationDetails evaluationDetails() {
    try {
      Constructor<EvaluationDetails> ctor =
          EvaluationDetails.class.getDeclaredConstructor(long.class, long.class, String.class);
      ctor.setAccessible(true);
      return ctor.newInstance(0L, 0L, "unit_test");
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to construct EvaluationDetails", e);
    }
  }

  private static DynamicConfig newDynamicConfig(
      String key, Map<String, Object> defaultValue, String idType) {
    try {
      Constructor<DynamicConfig> ctor =
          DynamicConfig.class.getDeclaredConstructor(
              String.class, Map.class, String.class, EvaluationDetails.class, String.class);
      ctor.setAccessible(true);
      return ctor.newInstance(key, defaultValue, "", evaluationDetails(), idType);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to construct DynamicConfig", e);
    }
  }

  private static String toString(InputStream stream) throws IOException {
    try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      StringBuilder builder = new StringBuilder();
      char[] buffer = new char[1024];
      int read;
      while ((read = reader.read(buffer)) != -1) {
        builder.append(buffer, 0, read);
      }
      return builder.toString();
    }
  }
}
