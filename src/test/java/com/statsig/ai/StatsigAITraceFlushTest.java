package com.statsig.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.statsig.Statsig;
import com.statsig.StatsigOptions;
import com.statsig.ai.trace.singleton.OtelSingleton;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class StatsigAITraceFlushTest {
  private InMemorySpanExporter spanExporter;
  private SdkTracerProvider tracerProvider;
  private Statsig core;
  private StatsigAI statsigAI;

  @Before
  public void setUp() throws Exception {
    spanExporter = InMemorySpanExporter.create();
    // A long schedule delay means spans are only exported when something flushes them.
    tracerProvider =
        SdkTracerProvider.builder()
            .addSpanProcessor(
                BatchSpanProcessor.builder(spanExporter)
                    .setScheduleDelay(1, TimeUnit.HOURS)
                    .build())
            .build();
    resetOtelSingleton();
    OtelSingleton.instantiate(tracerProvider);

    // An attached core: the AI instance doesn't own it, but still flushes traces.
    core = new Statsig("secret-core", new StatsigOptions.Builder().setDisableNetwork(true).build());
    statsigAI = new StatsigAI(StatsigInitConfig.coreAttached(core));
  }

  @After
  public void tearDown() throws Exception {
    tracerProvider.shutdown().join(5, TimeUnit.SECONDS);
    resetOtelSingleton();
    core.shutdown().get(5, TimeUnit.SECONDS);
  }

  @Test
  public void flushEventsForceFlushesTracerProvider() throws Exception {
    tracerProvider.get("flush-test").spanBuilder("pending-span").startSpan().end();
    assertTrue(spanExporter.getFinishedSpanItems().isEmpty());

    statsigAI.flushEvents().get(5, TimeUnit.SECONDS);

    assertEquals(1, spanExporter.getFinishedSpanItems().size());
    assertEquals("pending-span", spanExporter.getFinishedSpanItems().get(0).getName());
  }

  @Test
  public void shutdownFlushesWithoutShuttingDownTracerProvider() throws Exception {
    tracerProvider.get("flush-test").spanBuilder("before-shutdown").startSpan().end();

    statsigAI.shutdown().get(5, TimeUnit.SECONDS);
    assertEquals(1, spanExporter.getFinishedSpanItems().size());

    // The provider is still usable after the AI instance shuts down.
    tracerProvider.get("flush-test").spanBuilder("after-shutdown").startSpan().end();
    tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
    assertEquals(2, spanExporter.getFinishedSpanItems().size());
  }

  private static void resetOtelSingleton() throws Exception {
    Field instanceField = OtelSingleton.class.getDeclaredField("instance");
    instanceField.setAccessible(true);
    instanceField.set(null, null);
  }
}
