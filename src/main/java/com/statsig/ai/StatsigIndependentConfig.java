package com.statsig.ai;

import com.statsig.StatsigOptions;
import com.statsig.ai.internal.StatsigIntegrationConfig;

public class StatsigIndependentConfig implements StatsigIntegrationConfig {
  private String sdkKey;
  private StatsigOptions statsigOptions;

  public StatsigIndependentConfig(String sdkKey, StatsigOptions statsigOptions) {
    this.sdkKey = sdkKey;
    this.statsigOptions = statsigOptions;
  }

  public StatsigIndependentConfig() {}

  public void setSdkKey(String sdkKey) {
    this.sdkKey = sdkKey;
  }

  public void setStatsigOptions(StatsigOptions statsigOptions) {
    this.statsigOptions = statsigOptions;
  }

  public StatsigOptions getStatsigOptions() {
    return this.statsigOptions;
  }

  public String getSdkKey() {
    return this.sdkKey;
  }
}
