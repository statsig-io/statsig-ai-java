package com.statsig.ai;

import static org.junit.Assert.*;

import com.statsig.Statsig;
import com.statsig.StatsigOptions;
import org.junit.Test;

public class StatsigInitConfigTest {

  @Test
  public void independentFactorySetsValues() {
    StatsigOptions options = new StatsigOptions.Builder().build();

    StatsigInitConfig.CoreIndependent config = StatsigInitConfig.independent("secret-key", options);

    assertEquals("secret-key", config.getSdkKey());
    assertSame(options, config.getStatsigOptions());
  }

  @Test
  public void independentSettersUpdateFields() {
    StatsigInitConfig.CoreIndependent config = new StatsigInitConfig.CoreIndependent("secret-key");

    StatsigOptions options = new StatsigOptions.Builder().setServiceName("service").build();

    assertEquals("another-key", config.getSdkKey());
    assertSame(options, config.getStatsigOptions());
  }

  @Test
  public void attachedFactoryStoresCoreAndOptions() {
    StatsigOptions coreOptions =
        new StatsigOptions.Builder().setDisableNetwork(true).setServiceName("core").build();
    Statsig core = new Statsig("secret", coreOptions);

    StatsigInitConfig.CoreAttached config = StatsigInitConfig.attached(core);

    assertSame(core, config.getStatsigCoreInstance());
  }

  @Test(expected = NullPointerException.class)
  public void attachedFactoryRejectsNullCore() {
    StatsigInitConfig.attached(null);
  }

  @Test
  public void attachedSettersOverrideValues() {
    StatsigOptions coreOptions =
        new StatsigOptions.Builder().setDisableNetwork(true).setServiceName("core").build();
    Statsig core = new Statsig("secret", coreOptions);
    StatsigInitConfig.CoreAttached config = new StatsigInitConfig.CoreAttached(core);

    StatsigOptions newCoreOptions =
        new StatsigOptions.Builder().setDisableNetwork(true).setServiceName("core-new").build();
    Statsig newCore = new Statsig("secret-new", newCoreOptions);

    assertSame(newCore, config.getStatsigCoreInstance());
  }

  @Test(expected = NullPointerException.class)
  public void attachedSetterRejectsNullCore() {
    new StatsigInitConfig.CoreAttached(null);
  }
}
