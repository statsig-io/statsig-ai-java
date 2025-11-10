package com.statsig.ai;

import com.statsig.DynamicConfig;
import com.statsig.ParameterStore;
import com.statsig.Statsig;
import com.statsig.StatsigUser;
import java.util.Arrays;

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
    System.out.println("[Prompt.makePrompt] baseParamStoreName=" + baseParamStoreName);
    ParameterStore parameterStore = statsigServer.getParameterStore(user, baseParamStoreName);
    System.out.println("[Prompt.makePrompt] parameterStore=" + String.valueOf(parameterStore));

    String livePromptId = parameterStore.getString("live", "");
    System.out.println("[Prompt.makePrompt] livePromptId=" + livePromptId);
    Object[] candidatePromptIdsRaw = parameterStore.getArray("candidates", new String[0]);
    System.out.println(
        "[Prompt.makePrompt] candidatePromptIdsRaw=" + Arrays.toString(candidatePromptIdsRaw));
    String[] candidatePromptIds = new String[candidatePromptIdsRaw.length];
    for (int i = 0; i < candidatePromptIdsRaw.length; i++) {
      candidatePromptIds[i] = String.valueOf(candidatePromptIdsRaw[i]);
    }
    System.out.println(
        "[Prompt.makePrompt] candidatePromptIds=" + Arrays.toString(candidatePromptIds));
    DynamicConfig livePromptConfig = statsigServer.getDynamicConfig(user, livePromptId);
    System.out.println("[Prompt.makePrompt] livePromptConfig=" + String.valueOf(livePromptConfig));
    PromptConfig livePromptVariant = new PromptConfig(livePromptConfig);

    PromptConfig[] candidatePromptConfigs = new PromptConfig[candidatePromptIds.length];
    for (int i = 0; i < candidatePromptIds.length; i++) {
      DynamicConfig candidatePromptConfig =
          statsigServer.getDynamicConfig(user, candidatePromptIds[i]);
      System.out.println(
          "[Prompt.makePrompt] candidatePromptConfig("
              + i
              + ")="
              + String.valueOf(candidatePromptConfig));
      candidatePromptConfigs[i] = new PromptConfig(candidatePromptConfig);
    }

    return new Prompt(promptName, livePromptVariant, candidatePromptConfigs, user);
  }
}
