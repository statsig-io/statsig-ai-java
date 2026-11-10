package com.statsig.ai;

import com.statsig.ParameterStore;
import com.statsig.Statsig;
import com.statsig.StatsigUser;
import com.statsig.ai.internal.Utils;
import com.statsig.ai.trace.StatsigTraceConventions;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class StatsigAI {
  private static final Object LOCK = new Object();
  private static volatile StatsigAI sharedInstance;

  private final Statsig statsigServer;
  // True when this AI instance created the Statsig core itself and must manage its lifecycle
  private final boolean managesCoreLifecycle;
  // for inline targeting depth limit
  private static final int MAX_DEPTH = 300;

  /**
   * Creates an instance that is not managed by the static shared singleton.
   *
   * <p>Use this constructor when you need to maintain multiple independent AI instances at the same
   * time. The caller is responsible for holding on to the returned object and invoking {@link
   * #initialize()}, {@link #shutdown()}, and {@link #flushEvents()} when appropriate.
   */
  public StatsigAI(StatsigInitConfig config) {
    Statsig statsig = null;
    boolean managesLifecycle = false;

    if (config instanceof StatsigInitConfig.CoreIndependent) {
      StatsigInitConfig.CoreIndependent standalone = (StatsigInitConfig.CoreIndependent) config;
      if (standalone.getStatsigOptions() == null) {
        statsig = new Statsig(standalone.getSdkKey());
      } else {
        statsig = new Statsig(standalone.getSdkKey(), standalone.getStatsigOptions());
      }
      managesLifecycle = true;
    } else if (config instanceof StatsigInitConfig.CoreAttached) {
      StatsigInitConfig.CoreAttached integrated = (StatsigInitConfig.CoreAttached) config;
      statsig = integrated.getStatsigCoreInstance();
      managesLifecycle = false;
    } else {
      System.err.println("[StatsigAI] Invalid config provided; Statsig core not created.");
    }

    this.statsigServer = statsig;
    this.managesCoreLifecycle = managesLifecycle;
  }

  /**
   * Creates or replaces the shared singleton instance and initializes it if necessary.
   *
   * <p>This mirrors the Java core SDK pattern: call once during application startup, then fetch it
   * later with {@link #shared()} for convenience. Projects that prefer explicit multi-instance
   * management can skip this and directly use the public constructor above.
   */
  public static CompletableFuture<StatsigAI> newShared(StatsigInitConfig config) {
    StatsigAI instance = new StatsigAI(config);
    synchronized (LOCK) {
      sharedInstance = instance;
    }
    return instance.initialize().thenApply(ignored -> instance);
  }

  /** Returns {@code true} when {@link #shared()} will return a non-null value. */
  public static boolean hasShared() {
    return sharedInstance != null;
  }

  /**
   * Returns the shared singleton instance, if one has been created.
   *
   * <p>Callers that want to support both singleton and multi-instance flows should use this method
   * for the singleton path, and fall back to composing their own instance otherwise.
   */
  public static StatsigAI shared() {
    StatsigAI instance = sharedInstance;
    if (instance == null) {
      System.err.println(
          "[StatsigAI] No shared instance has been created yet. "
              + "Call newShared() before using it.");
      return null;
    }
    return instance;
  }

  public CompletableFuture<Void> initialize() {
    if (managesCoreLifecycle && statsigServer != null) {
      return statsigServer.initialize();
    }
    return CompletableFuture.completedFuture(null);
  }

  public CompletableFuture<Void> shutdown() {
    if (managesCoreLifecycle && statsigServer != null) {
      return statsigServer.shutdown();
    }
    return CompletableFuture.completedFuture(null);
  }

  public CompletableFuture<Void> flushEvents() {
    if (managesCoreLifecycle && statsigServer != null) {
      return statsigServer.flushEvents();
    }
    return CompletableFuture.completedFuture(null);
  }

  public Prompt getPrompt(StatsigUser user, String promptName) {
    String targetingParamStoreName = resolvePromptTargetingRules(user, promptName);
    String targetPromptName = targetingParamStoreName.split(":")[1];
    return Prompt.makePrompt(statsigServer, user, targetPromptName);
  }

  // ------------------------------------------------------------
  // Eval Grade Logging
  // ------------------------------------------------------------

  /**
   * Logs a {@code statsig::eval_result} event for a prompt version.
   *
   * <p>The grade is joined to a trace through {@code trace_id} / {@code span_id}: those set on
   * {@code evalGradeData} win, otherwise the active span's IDs are used.
   */
  public void logEvalGrade(
      StatsigUser user,
      PromptConfig promptVersion,
      double score,
      String graderName,
      AIEvalGradeData evalGradeData) {
    if (!Utils.isValidScore(score)) {
      System.err.println("[StatsigAI] Invalid score provided; skipping eval grade logging.");
      return;
    }
    AIEvalGradeData evalData = evalGradeData != null ? evalGradeData : new AIEvalGradeData();

    String activityID = evalData.getActivityID();
    StatsigUser eventUser =
        isSet(activityID)
            ? withCustomID(user, StatsigTraceConventions.ATTR_ACTIVITY_ID, activityID)
            : user;

    String traceID = evalData.getTraceID();
    String spanID = evalData.getSpanID();
    if (!isSet(traceID) && !isSet(spanID)) {
      SpanContext spanContext = Span.current().getSpanContext();
      if (spanContext.isValid()) {
        traceID = spanContext.getTraceId();
        spanID = spanContext.getSpanId();
      }
    }

    String promptName = promptVersion.getPromptName();
    String sessionID = evalData.getSessionID();
    Map<String, String> metadata = new HashMap<>();
    metadata.put("score", String.valueOf(score));
    metadata.put("session_id", sessionID != null ? sessionID : "");
    metadata.put("version_name", promptVersion.getName());
    metadata.put("version_id", promptVersion.getId());
    metadata.put("grader_name", graderName);
    metadata.put("ai_config_name", promptName);
    metadata.put("is_live", String.valueOf(promptVersion.isLiveForUser()));
    metadata.put("source", "sdk");
    if (isSet(traceID)) {
      metadata.put("trace_id", traceID);
    }
    if (isSet(spanID)) {
      metadata.put("span_id", spanID);
    }
    if (isSet(evalData.getReasoning())) {
      metadata.put("reasoning", evalData.getReasoning());
    }
    if (evalData.getMetadata() != null) {
      for (Map.Entry<String, String> entry : evalData.getMetadata().entrySet()) {
        if (entry.getKey() != null && entry.getValue() != null) {
          metadata.put(entry.getKey(), entry.getValue());
        }
      }
    }
    this.statsigServer.logEvent(eventUser, "statsig::eval_result", promptName, metadata);
  }

  // ------------------------------------------------------------
  // Helper methods
  // ------------------------------------------------------------

  private static boolean isSet(String value) {
    return value != null && !value.isEmpty();
  }

  // StatsigUser is immutable, so the caller's user is copied rather than modified.
  private static StatsigUser withCustomID(StatsigUser user, String idType, String id) {
    Map<String, String> customIDs = new HashMap<>();
    if (user.getCustomIDs() != null) {
      customIDs.putAll(user.getCustomIDs());
    }
    customIDs.put(idType, id);
    return new StatsigUser.Builder()
        .setUserID(user.getUserID())
        .setCustomIDs(customIDs)
        .setEmail(user.getEmail())
        .setIp(user.getIp())
        .setLocale(user.getLocale())
        .setAppVersion(user.getAppVersion())
        .setUserAgent(user.getUserAgent())
        .setCountry(user.getCountry())
        .setPrivateAttributes(user.getPrivateAttributes())
        .setCustom(user.getCustom())
        .build();
  }

  private String resolvePromptTargetingRules(StatsigUser user, String promptName) {
    String baseParamStoreName = "prompt:" + promptName;
    String currentParamStoreName = baseParamStoreName;

    ParameterStore currentParamStore = statsigServer.getParameterStore(user, currentParamStoreName);
    String nextParamStoreName = currentParamStore.getString("prompt_targeting_rules", "");

    int depth = 0;

    while (nextParamStoreName != null
        && !nextParamStoreName.isEmpty()
        && !nextParamStoreName.equals(currentParamStoreName)
        && depth < MAX_DEPTH) {
      ParameterStore nextParamStore = statsigServer.getParameterStore(user, nextParamStoreName);
      String possibleNextParamStoreName = nextParamStore.getString("prompt_targeting_rules", "");

      if (possibleNextParamStoreName == null
          || possibleNextParamStoreName.isEmpty()
          || possibleNextParamStoreName.equals(nextParamStoreName)) {
        currentParamStoreName = nextParamStoreName;
        break;
      }

      currentParamStoreName = nextParamStoreName;
      nextParamStoreName = possibleNextParamStoreName;

      depth++;
    }

    if (depth >= MAX_DEPTH) {
      System.err.printf(
          "[StatsigAI] Max targeting depth (%d) reached while resolving prompt: %s. "
              + "Possible circular reference starting from \"%s\".%n",
          MAX_DEPTH, promptName, baseParamStoreName);
      currentParamStoreName = baseParamStoreName;
    }

    return currentParamStoreName;
  }

  /**
   * Returns the underlying Statsig core instance for advanced integrations (e.g. telemetry).
   *
   * <p>May be {@code null} if initialization failed or an invalid config was provided.
   */
  public Statsig getStatsig() {
    return this.statsigServer;
  }
}
