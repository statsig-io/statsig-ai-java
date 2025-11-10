package com.statsig.ai;

import static org.junit.Assert.assertEquals;

import com.statsig.OutputLogger;
import com.statsig.Statsig;
import com.statsig.StatsigOptions;
import com.statsig.StatsigUser;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class PromptUsageTest {
  private MockWebServer mockWebServer;
  private Statsig statsigCore;

  @Before
  public void setUp() throws IOException, InterruptedException, ExecutionException {
    String downloadConfigSpecsJson = TestUtils.readResource("/eval_proj_dcs.json");

    mockWebServer = new MockWebServer();
    mockWebServer.start();

    mockWebServer.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(downloadConfigSpecsJson));

    StatsigOptions options =
        new StatsigOptions.Builder()
            .setSpecsUrl(mockWebServer.url("/v2/download_config_specs").toString())
            .setOutputLoggerLevel(OutputLogger.LogLevel.DEBUG)
            .build();

    statsigCore = new Statsig("secret-test-key", options);
    statsigCore.initialize().get();
  }

  @After
  public void tearDown() throws IOException, InterruptedException, ExecutionException {
    statsigCore.shutdown().get();
    mockWebServer.shutdown();
  }

  @Test
  public void promptUsageLoadsLiveAndCandidatesFromEvalSpecs() throws Exception {

    StatsigAI statsigAIInstance = new StatsigAI(StatsigInitConfig.coreAttached(statsigCore));
    StatsigUser user = new StatsigUser.Builder().setUserID("test_user").build();

    Prompt prompt = statsigAIInstance.getPrompt(user, "test_prompt");

    assertEquals("test_prompt", prompt.getName());
    assertEquals("6KGzeo8TR9JTL7CZl7vccd", prompt.getLivePrompt().getId());
    assertEquals("Version 1", prompt.getLivePrompt().getName());
    assertEquals(2, prompt.getCandidatePrompts().length);
    assertEquals("7jszgFEAi1KRA2Tot6qikg", prompt.getCandidatePrompts()[0].getId());
    assertEquals("Version 2", prompt.getCandidatePrompts()[0].getName());
    assertEquals("7CKLvQvOwjj2vjx12gFO0Z", prompt.getCandidatePrompts()[1].getId());
    assertEquals("Version 3", prompt.getCandidatePrompts()[1].getName());
  }
}
