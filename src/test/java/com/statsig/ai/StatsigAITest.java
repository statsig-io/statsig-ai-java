package com.statsig.ai;

import static org.junit.Assert.*;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.statsig.DynamicConfig;
import com.statsig.EvaluationDetails;
import com.statsig.Statsig;
import com.statsig.StatsigOptions;
import com.statsig.StatsigUser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;

public class StatsigAITest {
  @Before
  public void resetSharedInstance() throws Exception {
    Field field = StatsigAI.class.getDeclaredField("sharedInstance");
    field.setAccessible(true);
    field.set(null, null);
  }

  @Test
  public void sharedReturnsNullWhenNotInitialized() {
    assertFalse(StatsigAI.hasShared());
    assertNull(StatsigAI.shared());
  }

  @Test
  public void newSharedWithAttachedConfigStoresSingleton() throws Exception {
    StatsigOptions coreOptions = new StatsigOptions.Builder().setDisableNetwork(true).build();
    Statsig core = new Statsig("secret-core", coreOptions);
    StatsigInitConfig.CoreAttached config = StatsigInitConfig.attached(core);

    StatsigAI instance = StatsigAI.newShared(config).get(1, TimeUnit.SECONDS);

    assertNotNull(instance);
    assertTrue(StatsigAI.hasShared());
    assertSame(instance, StatsigAI.shared());
  }

  @Test
  public void constructorSupportsIndependentInstances() throws Exception {
    StatsigOptions optionsA = new StatsigOptions.Builder().setDisableNetwork(true).build();
    StatsigOptions optionsB = new StatsigOptions.Builder().setDisableNetwork(true).build();

    StatsigInitConfig.CoreIndependent configA = StatsigInitConfig.independent("secret-1", optionsA);
    StatsigInitConfig.CoreIndependent configB = StatsigInitConfig.independent("secret-2", optionsB);

    StatsigAI first = new StatsigAI(configA);
    StatsigAI second = new StatsigAI(configB);

    assertNotSame(first, second);

    first.initialize().get(1, TimeUnit.SECONDS);
    first.flushEvents().get(1, TimeUnit.SECONDS);
    first.shutdown().get(1, TimeUnit.SECONDS);

    second.initialize().get(1, TimeUnit.SECONDS);
    second.flushEvents().get(1, TimeUnit.SECONDS);
    second.shutdown().get(1, TimeUnit.SECONDS);
  }

  @Test
  public void promptUsageLoadsLiveAndCandidatesFromEvalSpecs() throws Exception {
    JSONObject specs = loadEvalSpecs();

    FakeStatsig fakeStatsig = buildFakeStatsig(specs);
    StatsigAI ai = new StatsigAI(StatsigInitConfig.attached(fakeStatsig));
    StatsigUser user = new StatsigUser.Builder().setUserID("test_user").build();

    Prompt prompt = ai.getPrompt(user, "test_prompt");

    assertEquals("test_prompt", prompt.getName());
    assertEquals("prompt:6KGzeo8TR9JTL7CZl7vccd", prompt.getLivePrompt().getId());
    assertEquals("Version 1", prompt.getLivePrompt().getName());
    assertEquals(2, prompt.getCandidatePrompts().length);
    assertEquals("prompt:7jszgFEAi1KRA2Tot6qikg", prompt.getCandidatePrompts()[0].getId());
    assertEquals("Version 2", prompt.getCandidatePrompts()[0].getName());
    assertEquals("prompt:7CKLvQvOwjj2vjx12gFO0Z", prompt.getCandidatePrompts()[1].getId());
    assertEquals("Version 3", prompt.getCandidatePrompts()[1].getName());
  }

  private static FakeStatsig buildFakeStatsig(JSONObject specs) throws Exception {
    JSONObject dynamicConfigs = specs.getJSONObject("dynamic_configs");

    Map<String, DynamicConfig> configMap = new HashMap<>();
    for (String key :
        Arrays.asList(
            "prompt:6KGzeo8TR9JTL7CZl7vccd",
            "prompt:7jszgFEAi1KRA2Tot6qikg",
            "prompt:7CKLvQvOwjj2vjx12gFO0Z")) {
      JSONObject configSpec = dynamicConfigs.getJSONObject(key);
      configMap.put(key, toDynamicConfig(key, configSpec));
    }

    Map<String, String> stringParams = new HashMap<>();
    Map<String, Object[]> arrayParams = new HashMap<>();

    JSONObject paramStores = specs.getJSONObject("param_stores");
    JSONObject promptStore = paramStores.getJSONObject("prompt:test_prompt");
    JSONObject parameters = promptStore.getJSONObject("parameters");

    JSONObject liveParam = parameters.getJSONObject("live");
    String liveValue;
    if ("layer".equals(liveParam.getString("ref_type"))) {
      String layerName = liveParam.getString("layer_name");
      JSONObject layerSpec = dynamicConfigs.getJSONObject(layerName);
      liveValue =
          layerSpec
              .getJSONObject("defaultValue")
              .getString(liveParam.getString("param_name"));
    } else {
      liveValue = liveParam.getString("value");
    }
    stringParams.put(parameterKey("prompt:test_prompt", "live"), liveValue);

    JSONObject candidatesParam = parameters.getJSONObject("candidates");
    JSONArray candidateValues = candidatesParam.getJSONArray("value");
    String[] candidateIds = candidateValues.toArray(new String[0]);
    arrayParams.put(parameterKey("prompt:test_prompt", "candidates"), candidateIds);

    return new FakeStatsig(configMap, stringParams, arrayParams);
  }

  private static JSONObject loadEvalSpecs() throws IOException {
    try (InputStream stream =
        StatsigAITest.class.getResourceAsStream("/eval_proj_dcs.json")) {
      assertNotNull("Fixture eval_proj_dcs.json should exist", stream);
      String json = toString(stream);
      return JSON.parseObject(json);
    }
  }

  private static DynamicConfig toDynamicConfig(String key, JSONObject spec) {
    JSONObject defaultObject = spec.getJSONObject("defaultValue");
    Map<String, Object> defaultValue = new HashMap<>(defaultObject);

    DynamicConfig config = newDynamicConfig(key, defaultValue, spec.getString("idType"));
    config.setRawJson(spec.toJSONString());
    return config;
  }

  private static String parameterKey(String storeName, String parameterName) {
    return storeName + ":" + parameterName;
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

  private static class FakeStatsig extends Statsig {
    private final Map<String, DynamicConfig> configs;
    private final Map<String, String> stringParams;
    private final Map<String, Object[]> arrayParams;

    FakeStatsig(
        Map<String, DynamicConfig> configs,
        Map<String, String> stringParams,
        Map<String, Object[]> arrayParams)
        throws Exception {
      super("secret-test", new StatsigOptions.Builder().setDisableNetwork(true).build());
      this.configs = configs;
      this.stringParams = stringParams;
      this.arrayParams = arrayParams;
    }

    @Override
    public DynamicConfig getDynamicConfig(StatsigUser user, String configName) {
      DynamicConfig config = configs.get(configName);
      if (config != null) {
        return config;
      }
      return newDynamicConfig(configName, new HashMap<>(), "userID");
    }

    @Override
    public String getStringFromParameterStore(
        StatsigUser user, String storeName, String parameterName, String defaultValue) {
      return stringParams.getOrDefault(parameterKey(storeName, parameterName), defaultValue);
    }

    @Override
    public Object[] getArrayFromParameterStore(
        StatsigUser user, String storeName, String parameterName, Object[] defaultValue) {
      return arrayParams.getOrDefault(parameterKey(storeName, parameterName), defaultValue);
    }
  }
}
