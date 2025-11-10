package com.statsig.ai;

import com.statsig.DynamicConfig;
import com.statsig.ParameterStore;
import com.statsig.Statsig;
import com.statsig.StatsigUser;

public class Prompt {
  public final String name;
  public PromptConfig live;
  public PromptConfig[] candidates;
  public StatsigUser user;

  Prompt(String name, PromptConfig livePrompt, PromptConfig[] candidatePrompts, StatsigUser user) {
    this.name = name;
    this.live = livePrompt;
    this.candidates = candidatePrompts;
    this.user = user;
  }

  public PromptConfig getLivePrompt() {
    return this.live;
  }

  public PromptConfig[] getCandidatePrompts() {
    return this.candidates;
  }

  public StatsigUser getUser() {
    return this.user;
  }

  public String getName() {
    return this.name;
  }

  static Prompt makePrompt(Statsig statsigServer, StatsigUser user, String promptName) {
    String baseParamStoreName = "prompt:" + promptName;
    ParameterStore parameterStore = statsigServer.getParameterStore(user, baseParamStoreName);

    String livePromptId = parameterStore.getString("live", "");
    Object[] candidatePromptIdsRaw = parameterStore.getArray("candidates", new String[0]);
    String[] candidatePromptIds = new String[candidatePromptIdsRaw.length];
    for (int i = 0; i < candidatePromptIdsRaw.length; i++) {
      candidatePromptIds[i] = String.valueOf(candidatePromptIdsRaw[i]);
    }
    DynamicConfig livePromptConfig = statsigServer.getDynamicConfig(user, livePromptId);
    PromptConfig livePromptVariant = new PromptConfig(livePromptConfig);

    PromptConfig[] candidatePromptConfigs = new PromptConfig[candidatePromptIds.length];
    for (int i = 0; i < candidatePromptIds.length; i++) {
      DynamicConfig candidatePromptConfig =
          statsigServer.getDynamicConfig(user, candidatePromptIds[i]);
      candidatePromptConfigs[i] = new PromptConfig(candidatePromptConfig);
    }

    return new Prompt(promptName, livePromptVariant, candidatePromptConfigs, user);
  }
}
