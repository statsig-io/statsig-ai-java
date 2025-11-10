package com.statsig.ai;

import static org.junit.Assert.*;

import com.statsig.Statsig;
import com.statsig.StatsigOptions;
import org.junit.Test;

public class StatsigInitConfigTest {

  @Test
  public void independentFactorySetsValues() {
    StatsigOptions options = new StatsigOptions.Builder().setSpecsSyncIntervalMs(5000).build();
    StatsigInitConfig.CoreIndependent config =
        StatsigInitConfig.coreIndependent("secret-key", options);

    assertEquals("secret-key", config.getSdkKey());
    assertSame(options, config.getStatsigOptions());
  }

  @Test
  public void attachedFactoryStoresCoreAndOptions() {
    StatsigOptions options =
        new StatsigOptions.Builder().setDisableNetwork(true).setServiceName("core").build();
    Statsig core = new Statsig("secret-key", options);
    StatsigInitConfig.CoreAttached config = StatsigInitConfig.coreAttached(core);

    assertSame(core, config.getStatsigCoreInstance());
  }
}
