package com.statsig.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.statsig.OutputLogger;
import com.statsig.Statsig;
import com.statsig.StatsigOptions;
import com.statsig.StatsigUser;
import com.statsig.ai.testutil.MockScrapiServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class PromptTargetingTest {
  private MockScrapiServer scrapi;
  private Statsig statsigCore;
  private StatsigAI statsigAI;

  @Before
  public void setUp() throws IOException, InterruptedException, ExecutionException {
    scrapi = MockScrapiServer.create();
    scrapi.start();
    scrapi.enqueueDownloadConfigSpecsResource("/eval_proj_dcs_targeting.json");

    StatsigOptions options =
        new StatsigOptions.Builder()
            .setSpecsUrl(scrapi.url("/v2/download_config_specs"))
            .setOutputLoggerLevel(OutputLogger.LogLevel.DEBUG)
            .build();

    statsigCore = new Statsig("secret-test-key", options);
    statsigCore.initialize().get();
    statsigAI = new StatsigAI(StatsigInitConfig.coreAttached(statsigCore));
  }

  @After
  public void tearDown() throws Exception {
    if (statsigCore != null) {
      statsigCore.shutdown().get();
      statsigCore = null;
    }
    if (scrapi != null) {
      scrapi.close();
      scrapi = null;
    }
    statsigAI = null;
  }

  @Test
  public void promptTargetingResolvesDirectRules() throws Exception {
    StatsigUser user = new StatsigUser.Builder().setUserID("test-user").build();

    Prompt prompt = statsigAI.getPrompt(user, "test-prompt-1");
    PromptVersion liveVersion = prompt.getLive();

    assertNotNull("Expected live prompt to be resolved", liveVersion);
    assertEquals("test-prompt-2", prompt.getName());
    assertEquals("2QGncLi0YSYj9zavJ825qB", liveVersion.getId());
    assertEquals("Version 1", liveVersion.getName());
    assertEquals(0.0, liveVersion.getTemperature(), 0.0);
    assertEquals(1000, liveVersion.getMaxTokens());
    assertEquals(1.0, liveVersion.getTopP(), 0.0);
    assertEquals(0.0, liveVersion.getFrequencyPenalty(), 0.0);
    assertEquals(0.0, liveVersion.getPresencePenalty(), 0.0);
    assertEquals("openai", liveVersion.getProvider());
    assertEquals("gpt-4o", liveVersion.getModel());

    Map<String, Object> workflowBody = liveVersion.getWorkflowBody();
    assertNotNull("Expected workflow body for targeted prompt", workflowBody);
    assertEquals("JSON", workflowBody.get("type"));
    assertEquals("{}", workflowBody.get("value"));

    assertEquals("gpt-5", liveVersion.getEvalModel());
    assertEquals("Live", liveVersion.getType());
    assertEquals("test-prompt-2", liveVersion.getPromptName());
  }

  @Test
  public void promptTargetingResolvesNestedRules() throws Exception {
    StatsigUser user = new StatsigUser.Builder().setUserID("test-user-1").build();

    Prompt prompt = statsigAI.getPrompt(user, "test-prompt-1");
    PromptVersion liveVersion = prompt.getLive();

    assertNotNull("Expected live prompt to be resolved", liveVersion);
    assertEquals("test-prompt-3", prompt.getName());
    assertEquals("36qqmfh8wHcKzjmX2My2RQ", liveVersion.getId());
    assertEquals("Version 1", liveVersion.getName());
    assertEquals(2.0, liveVersion.getTemperature(), 0.0);
    assertEquals(1000, liveVersion.getMaxTokens());
    assertEquals(1.0, liveVersion.getTopP(), 0.0);
    assertEquals(0.0, liveVersion.getFrequencyPenalty(), 0.0);
    assertEquals(0.0, liveVersion.getPresencePenalty(), 0.0);
    assertEquals("openai", liveVersion.getProvider());
    assertEquals("gpt-4.1", liveVersion.getModel());

    Map<String, Object> workflowBody = liveVersion.getWorkflowBody();
    assertNotNull("Expected workflow body for targeted prompt", workflowBody);
    assertEquals("JSON", workflowBody.get("type"));
    assertEquals("{}", workflowBody.get("value"));

    assertEquals("gpt-5", liveVersion.getEvalModel());
    assertEquals("Live", liveVersion.getType());
    assertEquals("test-prompt-3", liveVersion.getPromptName());
  }

  @Test
  public void promptTargetingHandlesCircularRulesGracefully() throws Exception {
    StatsigUser user = new StatsigUser.Builder().setUserID("test-user-circular").build();

    ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    PrintStream originalErr = System.err;
    System.setErr(new PrintStream(errContent));

    Prompt prompt = null;
    PromptVersion liveVersion = null;
    try {
      prompt = statsigAI.getPrompt(user, "test-prompt-circular-a");
      liveVersion = prompt.getLive();
    } finally {
      System.setErr(originalErr);
    }

    assertNotNull("Expected live prompt after circular targeting fallback", liveVersion);
    assertEquals("test-prompt-circular-a", prompt.getName());
    assertEquals("circular-a-version", liveVersion.getId());
    assertEquals("Circular A Version", liveVersion.getName());
    assertEquals(1.0, liveVersion.getTemperature(), 0.0);
    assertEquals(500, liveVersion.getMaxTokens());
    assertEquals("openai", liveVersion.getProvider());
    assertEquals("gpt-4", liveVersion.getModel());
    assertEquals("test-prompt-circular-a", liveVersion.getPromptName());

    String errOutput = new String(errContent.toByteArray(), StandardCharsets.UTF_8);
    assertTrue(errOutput.contains("Max targeting depth"));
    assertTrue(errOutput.contains("test-prompt-circular-a"));
    assertTrue(errOutput.contains("Possible circular reference"));
  }
}
