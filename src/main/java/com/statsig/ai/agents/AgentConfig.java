package com.statsig.ai.agents;

/**
 * An agent's live version and candidate versions, from {@code StatsigAI.getAgentConfig}. Each
 * version is a DAG whose root prompt is {@link AgentVersion#getRoot()}.
 */
public final class AgentConfig {
  private final String name;
  private final AgentVersion live;
  private final AgentVersion[] candidates;

  public AgentConfig(String name, AgentVersion live, AgentVersion[] candidates) {
    this.name = name;
    this.live = live;
    this.candidates = candidates.clone();
  }

  public String getName() {
    return name;
  }

  /** Returns the live version served to the user. */
  public AgentVersion getLive() {
    return live;
  }

  /** Returns a copy of the candidate versions. Empty when there are none. */
  public AgentVersion[] getCandidates() {
    return candidates.clone();
  }
}
