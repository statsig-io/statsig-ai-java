package com.statsig.ai.trace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.statsig.StatsigUser;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class StatsigSpanProcessorTest {
  private static final AttributeKey<String> USER_ID = AttributeKey.stringKey("statsig.user_id");
  private static final AttributeKey<String> CUSTOM_IDS =
      AttributeKey.stringKey("statsig.custom_ids");

  private InMemorySpanExporter exporter;
  private StatsigSpanProcessor processor;
  private SdkTracerProvider tracerProvider;
  private Tracer tracer;

  @Before
  public void setUp() {
    exporter = InMemorySpanExporter.create();
    // A long schedule delay means spans are only exported when something flushes them.
    processor =
        new StatsigSpanProcessor(
            BatchSpanProcessor.builder(exporter).setScheduleDelay(1, TimeUnit.HOURS).build());
    tracerProvider = SdkTracerProvider.builder().addSpanProcessor(processor).build();
    tracer = tracerProvider.get("statsig-processor-test");
  }

  @After
  public void tearDown() {
    tracerProvider.shutdown().join(5, TimeUnit.SECONDS);
  }

  private SpanData flushAndGet(String name) {
    processor.forceFlush().join(5, TimeUnit.SECONDS);
    for (SpanData span : exporter.getFinishedSpanItems()) {
      if (span.getName().equals(name)) {
        return span;
      }
    }
    throw new AssertionError("no span named " + name);
  }

  private void startAndEnd(String name) {
    tracer.spanBuilder(name).startSpan().end();
  }

  @Test
  public void stampsUserAttrsInsideUserContextOnly() {
    StatsigUser user =
        new StatsigUser.Builder()
            .setUserID("span-user")
            .setCustomIDs(Collections.singletonMap("org", "acme"))
            .build();

    StatsigContextUtils.withStatsigUserContext(user, () -> startAndEnd("inside"));
    startAndEnd("outside");

    SpanData inside = flushAndGet("inside");
    assertEquals("span-user", inside.getAttributes().get(USER_ID));
    assertEquals("{\"org\":\"acme\"}", inside.getAttributes().get(CUSTOM_IDS));

    SpanData outside = flushAndGet("outside");
    assertNull(outside.getAttributes().get(USER_ID));
    assertNull(outside.getAttributes().get(CUSTOM_IDS));
  }

  @Test
  public void stampsUserFromExplicitParentContext() {
    StatsigUser user = new StatsigUser.Builder().setUserID("parent-user").build();
    Context parent = StatsigContextUtils.withUser(Context.root(), user);

    tracer.spanBuilder("explicit-parent").setParent(parent).startSpan().end();

    SpanData span = flushAndGet("explicit-parent");
    assertEquals("parent-user", span.getAttributes().get(USER_ID));
    // No custom IDs, so no statsig.custom_ids.
    assertNull(span.getAttributes().get(CUSTOM_IDS));
  }

  @Test
  public void withStatsigUserContextReturnsTheBodysResult() {
    StatsigUser user = new StatsigUser.Builder().setUserID("result-user").build();

    String result =
        StatsigContextUtils.withStatsigUserContext(
            user,
            () -> {
              assertEquals(
                  "result-user", StatsigContextUtils.getActiveUser(Context.current()).getUserID());
              return "ok";
            });

    assertEquals("ok", result);
    assertNull(StatsigContextUtils.getActiveUser(Context.current()));
  }

  @Test
  public void doesNotStampFromStatsigContext() {
    // Like Node's StatsigSpanProcessor, only the user context is stamped on every span;
    // withStatsigContext attributes go on model call spans.
    StatsigUser user = new StatsigUser.Builder().setUserID("context-user").build();

    StatsigContextUtils.withStatsigContext(user, "activity", () -> startAndEnd("context-span"));

    SpanData span = flushAndGet("context-span");
    assertNull(span.getAttributes().get(USER_ID));
    assertNull(span.getAttributes().get(CUSTOM_IDS));
  }

  @Test
  public void forceFlushExportsBatchedSpans() {
    startAndEnd("pending");
    assertTrue(exporter.getFinishedSpanItems().isEmpty());

    assertTrue(processor.forceFlush().join(5, TimeUnit.SECONDS).isSuccess());
    assertEquals(1, exporter.getFinishedSpanItems().size());
  }

  @Test
  public void delegatesEveryCall() {
    SpanProcessor delegate = mock(SpanProcessor.class);
    CompletableResultCode flushed = new CompletableResultCode();
    CompletableResultCode shutDown = new CompletableResultCode();
    when(delegate.isEndRequired()).thenReturn(true);
    when(delegate.forceFlush()).thenReturn(flushed);
    when(delegate.shutdown()).thenReturn(shutDown);
    StatsigSpanProcessor wrapper = new StatsigSpanProcessor(delegate);

    // Start is always required so the processor can stamp attributes.
    assertTrue(wrapper.isStartRequired());
    assertTrue(wrapper.isEndRequired());
    ReadWriteSpan span = mock(ReadWriteSpan.class);
    wrapper.onStart(Context.root(), span);
    wrapper.onEnd(span);
    assertSame(flushed, wrapper.forceFlush());
    assertSame(shutDown, wrapper.shutdown());

    verify(delegate).onStart(Context.root(), span);
    verify(delegate).onEnd(span);
    verify(delegate).forceFlush();
    verify(delegate).shutdown();
  }

  @Test
  public void otelTracerProviderUsesStatsigSpanProcessor() {
    InMemorySpanExporter otelExporter = InMemorySpanExporter.create();
    SdkTracerProvider provider = Otel.buildTracerProvider(otelExporter);
    try {
      StatsigUser user = new StatsigUser.Builder().setUserID("otel-user").build();
      StatsigContextUtils.withStatsigUserContext(
          user, () -> provider.get("otel-test").spanBuilder("otel-span").startSpan().end());

      provider.forceFlush().join(5, TimeUnit.SECONDS);
      assertEquals(1, otelExporter.getFinishedSpanItems().size());
      SpanData span = otelExporter.getFinishedSpanItems().get(0);
      assertEquals("otel-user", span.getAttributes().get(USER_ID));
    } finally {
      provider.shutdown().join(5, TimeUnit.SECONDS);
    }
  }
}
