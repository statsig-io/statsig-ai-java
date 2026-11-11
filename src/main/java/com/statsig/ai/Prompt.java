package com.statsig.ai;

import com.statsig.DynamicConfig;
import com.statsig.ParameterStore;
import com.statsig.Statsig;
import com.statsig.StatsigUser;
import com.statsig.ai.trace.StatsigContextUtils;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.Objects;
import java.util.function.Consumer;
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

  /**
   * Runs {@code block} with the live version active, and returns its result. See {@link
   * #withVersion(PromptConfig, Function)}.
   */
  public <R> R withLive(Function<PromptConfig, R> block) {
    Objects.requireNonNull(block, "block");
    return withVersion(this.live, block);
  }

  /** Runs {@code consumer} with the live version active. See {@link #withLive(Function)}. */
  public void withLive(Consumer<PromptConfig> consumer) {
    Objects.requireNonNull(consumer, "consumer");
    withLive(
        liveConfig -> {
          consumer.accept(liveConfig);
          return null;
        });
  }

  /**
   * Runs {@code body} with {@code version} active, and returns its result. OpenAI model call spans
   * started inside it, and their {@code statsig::gen_ai} events, get {@code
   * statsig.llm.prompt_name}, {@code statsig.llm.prompt_version} and {@code
   * statsig.llm.prompt_version_id}, which Statsig uses to attribute online eval grades to the
   * version. It also puts this prompt's user in context, like {@link
   * StatsigContextUtils#withStatsigUserContext(StatsigUser, Supplier)}.
   *
   * <p>Use it with a candidate from {@link #getCandidatePrompts()}. An inner version replaces an
   * outer one.
   */
  public <R> R withVersion(PromptConfig version, Function<PromptConfig, R> body) {
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(body, "body");
    Context ctx =
        StatsigContextUtils.withPromptVersion(
            Context.current(), this.name, version.getName(), version.getId());
    ctx = StatsigContextUtils.withUser(ctx, this.user);
    try (Scope scope = ctx.makeCurrent()) {
      return body.apply(version);
    }
  }

  /**
   * Runs {@code body} with {@code version} active. See {@link #withVersion(PromptConfig,
   * Function)}.
   */
  public void withVersion(PromptConfig version, Consumer<PromptConfig> body) {
    Objects.requireNonNull(body, "body");
    withVersion(
        version,
        config -> {
          body.accept(config);
          return null;
        });
  }

  /**
   * Runs {@code supplier} with this prompt's name and user in context, and returns its result.
   * Spans started inside it get {@code statsig.llm.prompt_name} but no version, so use it for code
   * that runs before a version is chosen.
   */
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
