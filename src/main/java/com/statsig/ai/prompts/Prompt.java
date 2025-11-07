package com.statsig.ai.prompts;

import com.statsig.StatsigUser;

public class Prompt {
  public final String name;
  public PromptVariant livePrompt;
  public PromptVariant[] candidatePrompts;
  public StatsigUser user;

  public Prompt(
      String name, PromptVariant livePrompt, PromptVariant[] candidatePrompts, StatsigUser user) {
    this.name = name;
    this.livePrompt = livePrompt;
    this.candidatePrompts = candidatePrompts;
    this.user = user;
  }

  public PromptVariant getLivePrompt() {
    return this.livePrompt;
  }

  public PromptVariant[] getCandidatePrompts() {
    return this.candidatePrompts;
  }

  public StatsigUser getUser() {
    return this.user;
  }

  public String getName() {
    return this.name;
  }
}
