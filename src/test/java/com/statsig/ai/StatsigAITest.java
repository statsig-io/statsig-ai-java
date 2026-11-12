package com.statsig.ai;

import static org.junit.Assert.*;

import com.statsig.Statsig;
import com.statsig.StatsigOptions;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;

public class StatsigAITest {
  @Before
  public void resetSharedInstance() throws Exception {
    Field field = StatsigAI.class.getDeclaredField("sharedInstance");
    field.setAccessible(true);
    field.set(null, null);
  }

  @Test
  public void sharedReturnsNullWhenNotInitialized() {
    assertFalse(StatsigAI.hasShared());
    assertNull(StatsigAI.shared());
  }

  @Test
  public void newSharedWithAttachedConfigStoresSingleton() throws Exception {
    StatsigOptions coreOptions = new StatsigOptions.Builder().setDisableNetwork(true).build();
    Statsig core = new Statsig("secret-core", coreOptions);
    StatsigInitConfig.CoreAttached config = StatsigInitConfig.coreAttached(core);

    StatsigAI instance = StatsigAI.newShared(config).get(1, TimeUnit.SECONDS);

    assertNotNull(instance);
    assertTrue(StatsigAI.hasShared());
    assertSame(instance, StatsigAI.shared());
  }

  @Test
  public void newSharedKeepsTheExistingSharedInstance() throws Exception {
    StatsigAI first = StatsigAI.newShared(attached("secret-core")).get(1, TimeUnit.SECONDS);

    StatsigAI second = StatsigAI.newShared(attached("secret-other")).get(1, TimeUnit.SECONDS);

    assertSame(first, StatsigAI.shared());
    // Like Node, the second call returns an invalid instance rather than replacing the first.
    assertNotNull(second);
    assertNotSame(first, second);
    assertNotNull(second.getStatsig());
  }

  @Test
  public void removeSharedInstanceAllowsANewOne() throws Exception {
    StatsigAI first = StatsigAI.newShared(attached("secret-core")).get(1, TimeUnit.SECONDS);

    StatsigAI.removeSharedInstance();
    assertFalse(StatsigAI.hasShared());

    StatsigAI second = StatsigAI.newShared(attached("secret-other")).get(1, TimeUnit.SECONDS);
    assertNotSame(first, second);
    assertSame(second, StatsigAI.shared());
  }

  private static StatsigInitConfig.CoreAttached attached(String sdkKey) {
    StatsigOptions coreOptions = new StatsigOptions.Builder().setDisableNetwork(true).build();
    return StatsigInitConfig.coreAttached(new Statsig(sdkKey, coreOptions));
  }

  @Test
  public void constructorSupportsIndependentInstances() throws Exception {
    StatsigOptions optionsA = new StatsigOptions.Builder().setDisableNetwork(true).build();
    StatsigOptions optionsB = new StatsigOptions.Builder().setDisableNetwork(true).build();

    StatsigInitConfig.CoreIndependent configA =
        StatsigInitConfig.coreIndependent("secret-1", optionsA);
    StatsigInitConfig.CoreIndependent configB =
        StatsigInitConfig.coreIndependent("secret-2", optionsB);

    StatsigAI first = new StatsigAI(configA);
    StatsigAI second = new StatsigAI(configB);

    assertNotSame(first, second);

    first.initialize().get(1, TimeUnit.SECONDS);
    first.flushEvents().get(1, TimeUnit.SECONDS);
    first.shutdown().get(1, TimeUnit.SECONDS);

    second.initialize().get(1, TimeUnit.SECONDS);
    second.flushEvents().get(1, TimeUnit.SECONDS);
    second.shutdown().get(1, TimeUnit.SECONDS);
  }
}
