package com.statsig.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.statsig.OutputLogger;
import com.statsig.Statsig;
import com.statsig.StatsigOptions;
import com.statsig.StatsigUser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class PromptUsageTest {
  private MockWebServer mockWebServer;
  private Statsig statsigCore;
  private StatsigUser testUser;

  @Before
  public void setUp() throws IOException, InterruptedException, ExecutionException {
    String downloadConfigSpecsJson = readResource("/eval_proj_dcs.json");

    mockWebServer = new MockWebServer();
    mockWebServer.start();

    mockWebServer.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(downloadConfigSpecsJson));

    Map<String, Object> custom = new HashMap<>();
    custom.put("custom_field", "custom_value");

    testUser =
        new StatsigUser.Builder()
            .setUserID("test_user_id")
            .setEmail("test@example.com")
            .setCustom(custom)
            .build();

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

  private static String readResource(String resourcePath) throws IOException {
    try (InputStream stream = StatsigAITest.class.getResourceAsStream(resourcePath)) {
      assertNotNull("Expected resource " + resourcePath + " to exist", stream);
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
}
