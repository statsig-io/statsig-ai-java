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
