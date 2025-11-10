package com.statsig.ai;

import com.statsig.Statsig;
import com.statsig.StatsigUser;
import java.util.concurrent.CompletableFuture;

public final class StatsigAI {
  private static final Object LOCK = new Object();
  private static volatile StatsigAI sharedInstance;

  private final Statsig statsigServer;
  // True when this AI instance created the Statsig core itself and must manage its lifecycle
  private final boolean managesCoreLifecycle;

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
    return Prompt.makePrompt(statsigServer, user, promptName);
  }
}
