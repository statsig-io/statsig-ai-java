package com.statsig.ai.instrumentation.openai.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class OpenAIAttributesTest {

  private InMemorySpanExporter exporter;
  private SdkTracerProvider tracerProvider;

  @Before
  public void setUp() {
    exporter = InMemorySpanExporter.create();
    tracerProvider =
        SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
  }

  @After
  public void tearDown() {
    exporter.reset();
    tracerProvider.shutdown().join(5, TimeUnit.SECONDS);
  }

  @Test
  public void applyRequestAttributesPopulatesKeyFields() {
    Span span = tracerProvider.get("test").spanBuilder("req").startSpan();
    SpanTelemetry telemetry =
        new SpanTelemetry(
            span,
            "req",
            SpanTelemetry.DEFAULT_MAX_JSON_CHARS,
            SpanTelemetry.SpanEventLogger.noop());

    FakeParams params = new FakeParams();
    OpenAIAttributes.applyRequestAttributes(telemetry, "chat.completions", params);
    telemetry.end();

    SpanData data = exporter.getFinishedSpanItems().get(0);
    assertEquals(
        "chat.completions",
        data.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name")));
    assertEquals(
        "gpt-4o-mini", data.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model")));
    assertEquals(
        256L,
        data.getAttributes().get(AttributeKey.longKey("gen_ai.request.max_tokens")).longValue());
    assertNotNull(
        data.getAttributes().get(AttributeKey.stringKey("gen_ai.input.messages")));
    String toolDefs =
        data.getAttributes().get(AttributeKey.stringKey("gen_ai.tool.definitions"));
    assertNotNull(toolDefs);
    assertTrue(toolDefs.contains("search_tool"));
  }

  @Test
  public void applyResponseAndUsageAttributesPopulateSpan() {
    Span span = tracerProvider.get("test").spanBuilder("resp").startSpan();
    SpanTelemetry telemetry =
        new SpanTelemetry(
            span,
            "resp",
            SpanTelemetry.DEFAULT_MAX_JSON_CHARS,
            SpanTelemetry.SpanEventLogger.noop());

    FakeResponse response = new FakeResponse();
    OpenAIAttributes.applyResponseAttributes(telemetry, response);
    OpenAIAttributes.applyUsageAttributes(telemetry, response);
    telemetry.end();

    SpanData data = exporter.getFinishedSpanItems().get(0);
    assertEquals(
        "resp-id", data.getAttributes().get(AttributeKey.stringKey("gen_ai.response.id")));
    assertEquals(
        "gpt-4o-mini",
        data.getAttributes().get(AttributeKey.stringKey("gen_ai.response.model")));
    assertEquals(
        Arrays.asList("stop"),
        data.getAttributes().get(AttributeKey.stringArrayKey("gen_ai.response.finish_reasons")));
    assertEquals(
        Long.valueOf(42),
        data.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
    assertEquals(
        Long.valueOf(21),
        data.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens")));
  }

  private static final class FakeParams {
    public String model() {
      return "gpt-4o-mini";
    }

    public Long maxTokens() {
      return 256L;
    }

    public Double temperature() {
      return 0.25;
    }

    public List<FakeMessage> messages() {
      return Arrays.asList(
          new FakeMessage("system", "You are helpful"), new FakeMessage("user", "Hello!"));
    }

    public List<FakeTool> tools() {
      return Arrays.asList(new FakeTool("search_tool"));
    }
  }

  private static final class FakeMessage {
    private final String role;
    private final String content;

    private FakeMessage(String role, String content) {
      this.role = role;
      this.content = content;
    }

    public String role() {
      return role;
    }

    public String content() {
      return content;
    }
  }

  private static final class FakeTool {
    private final String name;

    private FakeTool(String name) {
      this.name = name;
    }

    public String name() {
      return name;
    }

    @Override
    public String toString() {
      return "{\"name\":\"" + name + "\"}";
    }
  }

  private static final class FakeResponse {
    public String id() {
      return "resp-id";
    }

    public String model() {
      return "gpt-4o-mini";
    }

    public List<FakeChoice> choices() {
      return Arrays.asList(new FakeChoice());
    }

    public FakeUsage usage() {
      return new FakeUsage();
    }
  }

  private static final class FakeChoice {
    public String finishReason() {
      return "stop";
    }
  }

  private static final class FakeUsage {
    public Long promptTokens() {
      return 42L;
    }

    public Long completionTokens() {
      return 21L;
    }
  }
}
