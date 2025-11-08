package com.statsig.ai;

import com.statsig.Statsig;
import com.statsig.StatsigOptions;

/**
 * Marker interface describing how the AI SDK should integrate with the underlying Statsig Java
 * core.
 *
 * <p>Use the static factory methods to create either an independent configuration, which manages
 * its own core lifecycle, or an attached configuration that links to an existing core instance.
 */
public interface StatsigInitConfig {
  static CoreIndependent independent(String sdkKey, StatsigOptions statsigOptions) {
    return new CoreIndependent(sdkKey, statsigOptions);
  }

  static CoreAttached attached(Statsig statsigCoreInstance, StatsigOptions statsigOptions) {
    return new CoreAttached(statsigCoreInstance, statsigOptions);
  }

  final class CoreIndependent implements StatsigInitConfig {
    private String sdkKey;
    private StatsigOptions statsigOptions;

    public CoreIndependent(String sdkKey, StatsigOptions statsigOptions) {
      this.sdkKey = sdkKey;
      this.statsigOptions = statsigOptions;
    }

    public CoreIndependent(String sdkKey) {
      this.sdkKey = sdkKey;
    }

    public String getSdkKey() {
      return sdkKey;
    }

    public StatsigOptions getStatsigOptions() {
      return statsigOptions;
    }
  }

  final class CoreAttached implements StatsigInitConfig {
    private Statsig statsigCoreInstance;
    private StatsigOptions statsigOptions;

    public CoreAttached(Statsig statsigCoreInstance, StatsigOptions statsigOptions) {
      this.statsigCoreInstance = statsigCoreInstance;
      this.statsigOptions = statsigOptions;
    }

    public CoreAttached(Statsig statsigCoreInstance) {
      this.statsigCoreInstance = statsigCoreInstance;
    }

    public Statsig getStatsigCoreInstance() {
      return statsigCoreInstance;
    }

    public StatsigOptions getStatsigOptions() {
      return statsigOptions;
    }
  }
}
