package com.statsig.ai.prompts;

import com.statsig.StatsigUser;

public class Prompt {
  public final String name;
  public PromptVersion live;
  public PromptVersion[] candidates;
  public StatsigUser user;

  public Prompt(
      String name, PromptVersion livePrompt, PromptVersion[] candidatePrompts, StatsigUser user) {
    this.name = name;
    this.live = livePrompt;
    this.candidates = candidatePrompts;
    this.user = user;
  }

  public PromptVersion getLivePrompt() {
    return this.live;
  }

  public PromptVersion[] getCandidatePrompts() {
    return this.candidates;
  }

  public StatsigUser getUser() {
    return this.user;
  }

  public String getName() {
    return this.name;
  }
}
