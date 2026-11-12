package com.statsig.ai.trace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.opentelemetry.context.Context;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class SpanTelemetryEventUserTest {

  @Test
  public void eventUserHasTheServiceVersionEnvironmentAndActivityID() {
    StatsigResourceAttributes resources =
        StatsigResourceAttributes.create(
            "checkout", "1.2.3", "staging", StatsigResourceAttributesTest.env(null, null));
    Context ctx = StatsigContextUtils.withStatsigContext(Context.root(), null, "activity-1");

    Map<String, String> expected = new LinkedHashMap<String, String>();
    expected.put("service", "checkout");
    expected.put("version", "1.2.3");
    expected.put("environment", "staging");
    expected.put("statsig.activity_id", "activity-1");
    assertEquals(expected, SpanTelemetry.eventUser(resources, ctx).getCustomIDs());
    assertEquals(
        SpanTelemetry.GEN_AI_EVENT_USER_ID, SpanTelemetry.eventUser(resources, ctx).getUserID());
  }

  @Test
  public void eventUserLeavesOutUnsetCustomIDs() {
    StatsigResourceAttributes serviceOnly =
        StatsigResourceAttributes.create(
            null, null, null, StatsigResourceAttributesTest.env("from-env", null));

    assertEquals(
        Collections.singletonMap("service", "from-env"),
        SpanTelemetry.eventUser(serviceOnly, Context.root()).getCustomIDs());

    StatsigResourceAttributes none =
        StatsigResourceAttributes.create(
            null, null, null, StatsigResourceAttributesTest.env(null, null));
    Map<String, String> customIDs = SpanTelemetry.eventUser(none, Context.root()).getCustomIDs();
    assertTrue(customIDs == null || customIDs.isEmpty());
  }
}
