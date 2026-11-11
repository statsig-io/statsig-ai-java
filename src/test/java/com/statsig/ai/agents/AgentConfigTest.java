package com.statsig.ai.agents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import com.statsig.Statsig;
import com.statsig.StatsigOptions;
import com.statsig.StatsigUser;
import com.statsig.ai.PromptVersion;
import com.statsig.ai.StatsigAI;
import com.statsig.ai.StatsigInitConfig;
import com.statsig.ai.testutil.MockScrapiServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AgentConfigTest {
  private MockScrapiServer scrapi;
  private Statsig statsigCore;
  private StatsigAI statsigAI;
  private final StatsigUser user = new StatsigUser.Builder().setUserID("agent-user").build();

  @Before
  public void setUp() throws Exception {
    scrapi = MockScrapiServer.create();
    scrapi.start();
    scrapi.enqueueDownloadConfigSpecsResource("/agent_dcs.json");

    StatsigOptions options =
        new StatsigOptions.Builder().setSpecsUrl(scrapi.url("/v2/download_config_specs")).build();
    statsigCore = new Statsig("secret-test-key", options);
    statsigCore.initialize().get();
    statsigAI = new StatsigAI(StatsigInitConfig.coreAttached(statsigCore));
  }

  @After
  public void tearDown() throws Exception {
    if (statsigCore != null) {
      statsigCore.shutdown().get();
    }
    if (scrapi != null) {
      scrapi.close();
    }
  }

  @Test
  public void liveVersionIsTheLiveDAGsRootPrompt() {
    AgentConfig agent = statsigAI.getAgentConfig(user, "support_agent");

    assertEquals("support_agent", agent.getName());
    PromptVersion root = agent.getLive().getRoot();
    assertEquals("live-root", root.getId());
    assertEquals("Live Root", root.getName());
    assertEquals("support_agent_root", root.getPromptName());
    assertEquals("gpt-4o", root.getModel());
    assertEquals(500, root.getMaxTokens());
    assertTrue(root.isLiveForUser());
  }

  @Test
  public void candidatesAreTheCandidateDAGsRootPrompts() {
    AgentConfig agent = statsigAI.getAgentConfig(user, "support_agent");

    AgentVersion[] candidates = agent.getCandidates();
    assertEquals(2, candidates.length);
    assertEquals("candidate-root-1", candidates[0].getRoot().getId());
    assertEquals("Candidate Root 1", candidates[0].getRoot().getName());
    assertEquals("gpt-4o-mini", candidates[0].getRoot().getModel());
    assertEquals("candidate-root-2", candidates[1].getRoot().getId());
    assertEquals("gpt-4.1", candidates[1].getRoot().getModel());
    for (AgentVersion candidate : candidates) {
      assertFalse(candidate.getRoot().isLiveForUser());
    }
    // A copy, so callers can't change the agent's candidates.
    assertNotSame(agent.getCandidates(), agent.getCandidates());
  }

  @Test
  public void emptyValuesGiveAnEmptyLiveRootAndNoCandidates() {
    assertEmpty(statsigAI.getAgentConfig(user, "empty_agent"), "empty_agent");
  }

  @Test
  public void unknownAgentGivesAnEmptyLiveRootAndNoCandidates() {
    assertEmpty(statsigAI.getAgentConfig(user, "no_such_agent"), "no_such_agent");
  }

  private static void assertEmpty(AgentConfig agent, String name) {
    assertEquals(name, agent.getName());
    PromptVersion root = agent.getLive().getRoot();
    assertEquals("", root.getId());
    assertEquals("", root.getName());
    assertEquals("", root.getModel());
    assertTrue(root.isLiveForUser());
    assertEquals(0, agent.getCandidates().length);
  }
}
