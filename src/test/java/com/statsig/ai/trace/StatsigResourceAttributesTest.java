package com.statsig.ai.trace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.Test;

public class StatsigResourceAttributesTest {

  @Test
  public void optionsAreUsedWhenSet() {
    StatsigResourceAttributes resources =
        StatsigResourceAttributes.create("checkout", "1.2.3", "staging", env("from-env", null));

    assertEquals("checkout", resources.getService());
    assertEquals("1.2.3", resources.getVersion());
    assertEquals("staging", resources.getEnvironment());
  }

  @Test
  public void serviceNameFallsBackToOtelServiceNameThenServiceName() {
    assertEquals(
        "otel-service",
        StatsigResourceAttributes.create(null, null, null, env("otel-service", "service"))
            .getService());
    assertEquals(
        "service",
        StatsigResourceAttributes.create(null, null, null, env(null, "service")).getService());
    // Empty values don't count, as in Node.
    assertEquals(
        "service",
        StatsigResourceAttributes.create("", null, null, env("", "service")).getService());
    assertNull(StatsigResourceAttributes.create(null, null, null, env(null, null)).getService());
  }

  @Test
  public void emptyVersionAndEnvironmentAreNull() {
    StatsigResourceAttributes resources =
        StatsigResourceAttributes.create(null, "", "", env(null, null));

    assertNull(resources.getVersion());
    assertNull(resources.getEnvironment());
  }

  static Function<String, String> env(String otelServiceName, String serviceName) {
    Map<String, String> vars = new HashMap<String, String>();
    vars.put(StatsigResourceAttributes.ENV_OTEL_SERVICE_NAME, otelServiceName);
    vars.put(StatsigResourceAttributes.ENV_SERVICE_NAME, serviceName);
    return vars::get;
  }
}
