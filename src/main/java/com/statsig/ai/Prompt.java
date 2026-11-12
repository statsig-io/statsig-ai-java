package com.statsig.ai;

import com.statsig.DynamicConfig;
import com.statsig.ParameterStore;
import com.statsig.Statsig;
import com.statsig.StatsigUser;
import com.statsig.ai.trace.StatsigContextUtils;
import com.statsig.ai.trace.StatsigTraceConventions;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.Objects;
import java.util.function.Supplier;

public class Prompt {
  public final String name;
  private final PromptVersion live;
  private final PromptVersion[] candidates;
  private final StatsigUser user;

  Prompt(String name, PromptVersion live, PromptVersion[] candidates, StatsigUser user) {
    this.name = name;
    this.live = live;
    this.candidates = candidates;
    this.user = user;
  }

  /** Returns the live version served to the user. */
  public PromptVersion getLive() {
    return this.live;
  }

  /** Returns a copy of the candidate versions. */
  public PromptVersion[] getCandidates() {
    return this.candidates.clone();
  }

  public StatsigUser getUser() {
    return this.user;
  }

  public String getName() {
    return this.name;
  }

  /**
   * Runs {@code body} with the live version active, and returns its result. Get the live version
   * from {@link #getLive()}. See {@link #withVersion(PromptVersion, Supplier)}.
   */
  public <R> R withLive(Supplier<R> body) {
    Objects.requireNonNull(body, "body");
    return withVersion(this.live, body);
  }

  /** Runs {@code body} with the live version active. See {@link #withLive(Supplier)}. */
  public void withLive(Runnable body) {
    Objects.requireNonNull(body, "body");
    withLive(
        () -> {
          body.run();
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
   * <p>Use it with a candidate from {@link #getCandidates()}. An inner version replaces an outer
   * one.
   */
  public <R> R withVersion(PromptVersion version, Supplier<R> body) {
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(body, "body");
    Context ctx =
        StatsigContextUtils.withPromptVersion(
            Context.current(), this.name, version.getName(), version.getId());
    ctx = StatsigContextUtils.withUser(ctx, this.user);
    try (Scope scope = ctx.makeCurrent()) {
      return body.get();
    }
  }

  /**
   * Runs {@code body} with {@code version} active. See {@link #withVersion(PromptVersion,
   * Supplier)}.
   */
  public void withVersion(PromptVersion version, Runnable body) {
    Objects.requireNonNull(body, "body");
    withVersion(
        version,
        () -> {
          body.run();
          return null;
        });
  }

  /**
   * Runs {@code supplier} with this prompt's name and user in context, and returns its result, for
   * code that runs before a version is chosen. Spans started inside it get {@code
   * statsig.llm.prompt_name}. It keeps any version already in context, so inside {@link
   * #withVersion(PromptVersion, Supplier)} they also get that version.
   */
  public <R> R withContext(Supplier<R> supplier) {
    Objects.requireNonNull(supplier, "supplier");
    Context ctx = withPromptName(Context.current());
    ctx = StatsigContextUtils.withUser(ctx, this.user);
    try (Scope scope = ctx.makeCurrent()) {
      return supplier.get();
    }
  }

  /**
   * Runs {@code runnable} with this prompt's name and user in context. See {@link
   * #withContext(Supplier)}.
   */
  public void withContext(Runnable runnable) {
    Objects.requireNonNull(runnable, "runnable");
    withContext(
        () -> {
          runnable.run();
          return null;
        });
  }

  // Sets only the prompt name, keeping any version and version ID already in ctx.
  private Context withPromptName(Context ctx) {
    return ctx.with(StatsigTraceConventions.CTX_ACTIVE_PROMPT, this.name == null ? "" : this.name);
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
    DynamicConfig liveDynamicConfig = statsigServer.getDynamicConfig(user, livePromptId);
    PromptVersion liveVersion = new PromptVersion(liveDynamicConfig, true);

    PromptVersion[] candidateVersions = new PromptVersion[candidatePromptIds.length];
    for (int i = 0; i < candidatePromptIds.length; i++) {
      DynamicConfig candidateDynamicConfig =
          statsigServer.getDynamicConfig(user, candidatePromptIds[i]);
      candidateVersions[i] = new PromptVersion(candidateDynamicConfig, false);
    }

    return new Prompt(promptName, liveVersion, candidateVersions, user);
  }
}
