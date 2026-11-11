package com.statsig.ai;

import static org.junit.Assert.assertEquals;

import com.statsig.Statsig;
import com.statsig.StatsigOptions;
import com.statsig.StatsigUser;
import com.statsig.ai.testutil.MockScrapiServer;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class PromptUsageTest {
  private MockScrapiServer scrapi;
  private Statsig statsigCore;

  @Before
  public void setUp() throws IOException, InterruptedException, ExecutionException {
    scrapi = MockScrapiServer.create();
    scrapi.start();
    scrapi.enqueueDownloadConfigSpecsResource("/eval_proj_dcs.json");

    StatsigOptions options =
        new StatsigOptions.Builder().setSpecsUrl(scrapi.url("/v2/download_config_specs")).build();

    statsigCore = new Statsig("secret-test-key", options);
    statsigCore.initialize().get();
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
  }

  @Test
  public void promptUsageLoadsLiveAndCandidatesFromEvalSpecs() throws Exception {

    StatsigAI statsigAIInstance = new StatsigAI(StatsigInitConfig.coreAttached(statsigCore));
    StatsigUser user = new StatsigUser.Builder().setUserID("test_user").build();

    Prompt prompt = statsigAIInstance.getPrompt(user, "test_prompt");

    assertEquals("test_prompt", prompt.getName());
    assertEquals("6KGzeo8TR9JTL7CZl7vccd", prompt.getLive().getId());
    assertEquals("Version 1", prompt.getLive().getName());
    assertEquals(2, prompt.getCandidates().length);
    assertEquals("7jszgFEAi1KRA2Tot6qikg", prompt.getCandidates()[0].getId());
    assertEquals("Version 2", prompt.getCandidates()[0].getName());
    assertEquals("7CKLvQvOwjj2vjx12gFO0Z", prompt.getCandidates()[1].getId());
    assertEquals("Version 3", prompt.getCandidates()[1].getName());
  }
}
