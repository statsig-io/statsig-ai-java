package com.statsig.ai;

import com.statsig.DynamicConfig;
import com.statsig.ParameterStore;
import com.statsig.Statsig;
import com.statsig.StatsigUser;
import com.statsig.ai.trace.StatsigContextUtils;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

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

  public <R> R withLive(Function<PromptConfig, R> block) {
    Objects.requireNonNull(block, "block");
    Context ctx = Context.current();
    ctx = StatsigContextUtils.withPrompt(ctx, this.name, this.live.getName());
    ctx = StatsigContextUtils.withUser(ctx, this.user);
    try (Scope scope = ctx.makeCurrent()) {
      return block.apply(this.live);
    }
  }

  public void withLive(java.util.function.Consumer<PromptConfig> consumer) {
    Objects.requireNonNull(consumer, "consumer");
    withLive(
        liveConfig -> {
          consumer.accept(liveConfig);
          return null;
        });
  }

  public <R> R withContext(Supplier<R> supplier) {
    Objects.requireNonNull(supplier, "supplier");
    Context ctx = Context.current();
    ctx = StatsigContextUtils.withPrompt(ctx, this.name, null);
    ctx = StatsigContextUtils.withUser(ctx, this.user);
    try (Scope scope = ctx.makeCurrent()) {
      return supplier.get();
    }
  }

  public void withContext(Runnable runnable) {
    Objects.requireNonNull(runnable, "runnable");
    withContext(
        () -> {
          runnable.run();
          return null;
        });
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
    PromptConfig livePromptVariant = new PromptConfig(livePromptConfig, true);

    PromptConfig[] candidatePromptConfigs = new PromptConfig[candidatePromptIds.length];
    for (int i = 0; i < candidatePromptIds.length; i++) {
      DynamicConfig candidatePromptConfig =
          statsigServer.getDynamicConfig(user, candidatePromptIds[i]);
      candidatePromptConfigs[i] = new PromptConfig(candidatePromptConfig, false);
    }

    return new Prompt(promptName, livePromptVariant, candidatePromptConfigs, user);
  }
}
