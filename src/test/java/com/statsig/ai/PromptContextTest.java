package com.statsig.ai;

import static org.junit.Assert.*;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.statsig.DynamicConfig;
import com.statsig.EvaluationDetails;
import com.statsig.StatsigUser;
import com.statsig.ai.instrumentation.context.UserContext;
import com.statsig.ai.trace.StatsigContextUtils;
import com.statsig.ai.trace.StatsigTraceConventions;
import io.opentelemetry.context.Context;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class PromptContextTest {

  @Test
  public void withLiveSetsPromptAndUserContext() throws Exception {
    Prompt prompt = buildPrompt("prompt-user");

    prompt.withLive(
        live -> {
          Context ctx = Context.current();
          assertEquals(prompt.getName(), ctx.get(StatsigTraceConventions.CTX_ACTIVE_PROMPT));
          assertEquals(live.getName(), ctx.get(StatsigTraceConventions.CTX_ACTIVE_PROMPT_VERSION));

          UserContext userCtx = StatsigContextUtils.getActiveUser(ctx);
          assertNotNull(userCtx);
          assertEquals("prompt-user", userCtx.getUserID());
          return null;
        });
  }

  @Test
  public void withContextOmitsPromptVersion() throws Exception {
    Prompt prompt = buildPrompt("prompt-user-2");

    prompt.withContext(
        () -> {
          Context ctx = Context.current();
          assertEquals(prompt.getName(), ctx.get(StatsigTraceConventions.CTX_ACTIVE_PROMPT));
          assertNull(ctx.get(StatsigTraceConventions.CTX_ACTIVE_PROMPT_VERSION));
          UserContext userCtx = StatsigContextUtils.getActiveUser(ctx);
          assertNotNull(userCtx);
          assertEquals("prompt-user-2", userCtx.getUserID());
          return null;
        });
  }

  private static Prompt buildPrompt(String userId) throws Exception {
    JSONObject payload = JSON.parseObject(TestUtils.readResource("/prompt.json"));
    PromptConfig config = new PromptConfig(toDynamicConfig(payload));
    StatsigUser user = new StatsigUser.Builder().setUserID(userId).build();
    return new Prompt(config.getPromptName(), config, new PromptConfig[0], user);
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
}
