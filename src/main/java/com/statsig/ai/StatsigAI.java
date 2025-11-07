package com.statsig.ai;

import com.statsig.Statsig;
import com.statsig.ai.internal.StatsigIntegrationConfig;
import java.util.concurrent.CompletableFuture;

public final class StatsigAI {
  private static final Object LOCK = new Object();
  private static volatile StatsigAI sharedInstance;

  private final Statsig statsigCoreInstance;
  private final boolean ownsCoreInstance;

  /**
   * Creates an instance that is not managed by the static shared singleton.
   *
   * <p>Use this constructor when you need to maintain multiple independent AI instances at the same
   * time. The caller is
   * responsible for holding on to the returned object and invoking {@link #initialize()},
   * {@link #shutdown()}, and {@link #flushEvents()} when appropriate.
   */
  public StatsigAI(StatsigIntegrationConfig config) {
    Statsig core = null;
    boolean owns = false;

    if (config instanceof StatsigIndependentConfig) {
      StatsigIndependentConfig standalone = (StatsigIndependentConfig) config;
      core = new Statsig(standalone.getSdkKey(), standalone.getStatsigOptions());
      owns = true;
    } else if (config instanceof StatsigCoreAttachedConfig) {
      StatsigCoreAttachedConfig integrated = (StatsigCoreAttachedConfig) config;
      core = integrated.getStatsigInstance();
      owns = false;
    } else {
      System.err.println("[StatsigAI] Invalid config provided; Statsig core not created.");
    }

    this.statsigCoreInstance = core;
    this.ownsCoreInstance = owns;
  }

  /**
   * Creates or replaces the shared singleton instance and initializes it if necessary.
   *
   * <p>This mirrors the Java core SDK pattern: call once during application startup, then fetch it
   * later with {@link #shared()} for convenience. Projects that prefer explicit multi-instance
   * management can skip this and directly use the public constructor above.
   */
  public static CompletableFuture<StatsigAI> newShared(StatsigIntegrationConfig config) {
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

  /**
   * Initializes the underlying Statsig core if this instance created it.
   *
   * <p>In multi-instance scenarios you should call this after constructing the instance. When the
   * instance attaches to an already initialized core, this is a no-op.
   */
  public CompletableFuture<Void> initialize() {
    if (ownsCoreInstance && statsigCoreInstance != null) {
      return statsigCoreInstance.initialize();
    }
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Shuts down the underlying Statsig core if this instance owns the lifecycle.
   *
   * <p>Use this in multi-instance setups or during application shutdown to cleanly release
   * resources.
   */
  public CompletableFuture<Void> shutdown() {
    if (ownsCoreInstance && statsigCoreInstance != null) {
      return statsigCoreInstance.shutdown();
    }
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Flushes events on the underlying Statsig core.
   *
   * <p>This method is safe to call whether the instance created its own core or attached to an
   * existing one.
   */
  public CompletableFuture<Void> flushEvents() {
    if (statsigCoreInstance != null) {
      return statsigCoreInstance.flushEvents();
    }
    return CompletableFuture.completedFuture(null);
  }
}
