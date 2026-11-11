package com.statsig.ai.trace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class IncomingTraceContextTest {
  private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
  private static final String SPAN_ID = "b7ad6b7169203331";
  private static final String TRACEPARENT = "00-" + TRACE_ID + "-" + SPAN_ID + "-01";

  private InMemorySpanExporter exporter;
  private SdkTracerProvider tracerProvider;
  private Tracer tracer;

  @Before
  public void setUp() {
    exporter = InMemorySpanExporter.create();
    tracerProvider =
        SdkTracerProvider.builder()
            .addSpanProcessor(new StatsigSpanProcessor(SimpleSpanProcessor.create(exporter)))
            .build();
    tracer = tracerProvider.get("incoming-trace-test");
  }

  @After
  public void tearDown() {
    tracerProvider.shutdown().join(5, TimeUnit.SECONDS);
  }

  private static Map<String, String> headers(String... keyValues) {
    Map<String, String> map = new HashMap<String, String>();
    for (int i = 0; i < keyValues.length; i += 2) {
      map.put(keyValues[i], keyValues[i + 1]);
    }
    return map;
  }

  private SpanData spanNamed(String name) {
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
  public void validTraceparentMakesSpansJoinTheTrace() {
    String result =
        StatsigContextUtils.withIncomingTraceContext(
            headers("traceparent", TRACEPARENT, "tracestate", "statsig=eval"),
            () -> {
              Span workflow = tracer.spanBuilder("workflow").startSpan();
              try (Scope ignored = workflow.makeCurrent()) {
                startAndEnd("tool");
              } finally {
                workflow.end();
              }
              return "ok";
            });

    assertEquals("ok", result);
    SpanData workflow = spanNamed("workflow");
    assertEquals(TRACE_ID, workflow.getTraceId());
    assertEquals(SPAN_ID, workflow.getParentSpanId());
    assertTrue(workflow.getParentSpanContext().isRemote());
    assertEquals("eval", workflow.getSpanContext().getTraceState().get("statsig"));

    SpanData tool = spanNamed("tool");
    assertEquals(TRACE_ID, tool.getTraceId());
    assertEquals(workflow.getSpanId(), tool.getParentSpanId());

    // The incoming context only applies inside the call.
    assertFalse(Span.current().getSpanContext().isValid());
  }

  @Test
  public void matchesHeaderNamesCaseInsensitively() {
    StatsigContextUtils.withIncomingTraceContext(
        headers("Traceparent", TRACEPARENT, "TraceState", "a=1,b=2", "Content-Type", "text/plain"),
        () -> startAndEnd("tool"));

    SpanData tool = spanNamed("tool");
    assertEquals(TRACE_ID, tool.getTraceId());
    assertEquals(SPAN_ID, tool.getParentSpanId());
    TraceState traceState = tool.getSpanContext().getTraceState();
    assertEquals("1", traceState.get("a"));
    assertEquals("2", traceState.get("b"));
  }

  @Test
  public void readsHeadersWithAGetter() {
    // Like HttpServletRequest.getHeader, which matches names case-insensitively.
    Map<String, String> servletHeaders = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
    servletHeaders.put("TRACEPARENT", TRACEPARENT);

    String result =
        StatsigContextUtils.withIncomingTraceContext(
            name -> servletHeaders.get(name),
            () -> {
              startAndEnd("supplier-tool");
              return "ok";
            });
    StatsigContextUtils.withIncomingTraceContext(
        servletHeaders::get, () -> startAndEnd("runnable-tool"));

    assertEquals("ok", result);
    for (String name : Arrays.asList("supplier-tool", "runnable-tool")) {
      SpanData tool = spanNamed(name);
      assertEquals(TRACE_ID, tool.getTraceId());
      assertEquals(SPAN_ID, tool.getParentSpanId());
    }
  }

  @Test
  public void missingOrMalformedHeadersRunUnchanged() {
    List<Map<String, String>> cases =
        Arrays.asList(
            Collections.<String, String>emptyMap(),
            headers("content-type", "application/json"),
            headers("traceparent", "not-a-traceparent"),
            headers("traceparent", "00-00000000000000000000000000000000-" + SPAN_ID + "-01"),
            headers("traceparent", "ff-" + TRACE_ID + "-" + SPAN_ID + "-01"),
            headers("tracestate", "a=1"),
            null);

    for (Map<String, String> headers : cases) {
      Context ctx = Context.current();
      assertSame(ctx, StatsigContextUtils.withIncomingTraceContext(ctx, headers));

      exporter.reset();
      assertEquals(
          "ok",
          StatsigContextUtils.withIncomingTraceContext(
              headers,
              () -> {
                startAndEnd("tool");
                return "ok";
              }));
      SpanData tool = spanNamed("tool");
      assertFalse(String.valueOf(headers), tool.getParentSpanContext().isValid());
      assertNotEquals(TRACE_ID, tool.getTraceId());
    }

    Context ctx = Context.current();
    assertSame(ctx, StatsigContextUtils.withIncomingTraceContext(ctx, name -> null));
    assertSame(
        ctx, StatsigContextUtils.withIncomingTraceContext(ctx, (Function<String, String>) null));
  }

  @Test
  public void keepsACurrentSpanAlreadyInTheTrace() {
    SpanContext remote =
        SpanContext.createFromRemoteParent(
            TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault());
    Span server =
        tracer
            .spanBuilder("POST /agent")
            .setParent(Context.root().with(Span.wrap(remote)))
            .startSpan();

    try (Scope ignored = server.makeCurrent()) {
      StatsigContextUtils.withIncomingTraceContext(
          headers("traceparent", TRACEPARENT), () -> startAndEnd("tool"));
    } finally {
      server.end();
    }

    SpanData tool = spanNamed("tool");
    assertEquals(TRACE_ID, tool.getTraceId());
    assertEquals(server.getSpanContext().getSpanId(), tool.getParentSpanId());
  }

  @Test
  public void replacesASpanFromAnotherTraceAndKeepsOtherContextValues() {
    StatsigContextUtils.withStatsigEnvironment(
        "eval",
        () -> {
          Span unrelated = tracer.spanBuilder("unrelated").startSpan();
          try (Scope ignored = unrelated.makeCurrent()) {
            StatsigContextUtils.withIncomingTraceContext(
                headers("traceparent", TRACEPARENT), () -> startAndEnd("tool"));
          } finally {
            unrelated.end();
          }
        });

    SpanData tool = spanNamed("tool");
    assertEquals(TRACE_ID, tool.getTraceId());
    assertEquals(SPAN_ID, tool.getParentSpanId());
    assertEquals("eval", tool.getAttributes().get(AttributeKey.stringKey("statsig.environment")));
  }
}
