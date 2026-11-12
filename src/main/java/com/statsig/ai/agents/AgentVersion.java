package com.statsig.ai.agents;

import com.statsig.DynamicConfig;
import com.statsig.ai.PromptVersion;

/** A version of an agent: its root prompt. */
public final class AgentVersion {
  private final PromptVersion root;

  /**
   * @param rootConfig the root prompt's dynamic config
   * @param isLiveForUser {@code true} for the agent's live version, {@code false} for a candidate
   */
  public AgentVersion(DynamicConfig rootConfig, boolean isLiveForUser) {
    this.root = new PromptVersion(rootConfig, isLiveForUser);
  }

  /**
   * Returns the root prompt. It's live for the user only for the agent's live version, so eval
   * grades logged for it get the right {@code is_live}.
   */
  public PromptVersion getRoot() {
    return root;
  }
}
