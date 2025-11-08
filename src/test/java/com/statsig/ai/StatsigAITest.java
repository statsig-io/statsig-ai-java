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
    StatsigOptions coreOptions =
        new StatsigOptions.Builder().setDisableNetwork(true).setServiceName("core").build();
    Statsig core = new Statsig("secret-core", coreOptions);
    StatsigOptions attachedOptions =
        new StatsigOptions.Builder().setDisableNetwork(true).setServiceName("attached").build();
    StatsigInitConfig.CoreAttached config = StatsigInitConfig.attached(core, attachedOptions);

    StatsigAI instance = StatsigAI.newShared(config).get(1, TimeUnit.SECONDS);

    assertNotNull(instance);
    assertTrue(StatsigAI.hasShared());
    assertSame(instance, StatsigAI.shared());
  }

  @Test
  public void constructorSupportsIndependentInstances() {
    StatsigOptions optionsA =
        new StatsigOptions.Builder()
            .setDisableNetwork(true)
            .setServiceName("independent-a")
            .build();
    StatsigOptions optionsB =
        new StatsigOptions.Builder()
            .setDisableNetwork(true)
            .setServiceName("independent-b")
            .build();
    StatsigInitConfig.CoreIndependent configA =
        new StatsigInitConfig.CoreIndependent("secret-1", optionsA);
    StatsigInitConfig.CoreIndependent configB =
        new StatsigInitConfig.CoreIndependent("secret-2", optionsB);

    StatsigAI first = new StatsigAI(configA);
    StatsigAI second = new StatsigAI(configB);

    assertNotSame(first, second);

    assertTrue(first.initialize().isDone());
    assertTrue(first.flushEvents().isDone());
    assertTrue(first.shutdown().isDone());

    assertTrue(second.initialize().isDone());
    assertTrue(second.flushEvents().isDone());
    assertTrue(second.shutdown().isDone());
  }
}
