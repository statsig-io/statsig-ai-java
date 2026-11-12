package com.statsig.ai.instrumentation.gemini;

import static com.statsig.ai.instrumentation.PlainJson.map;

import com.google.genai.AsyncModels;
import com.google.genai.Models;
import com.google.genai.ResponseStream;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.statsig.ai.instrumentation.GenAISpan;
import com.statsig.ai.instrumentation.GenAIStreamTracker;
import com.statsig.ai.instrumentation.GenAITelemetry;
import com.statsig.ai.instrumentation.openai.GenAICaptureOptions;
import io.opentelemetry.context.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A traced {@code client.models}, from {@link GeminiWrapper#wrap}. Its {@code generateContent} and
 * {@code generateContentStream} take the same arguments as {@link Models}'s, and record each call
 * as a model call span, like Node's {@code wrapGemini}. Use {@link #getModels()} for other calls,
 * and {@link #async()} for {@code client.async.models}.
 */
public final class GeminiModels {
  private final Models models;
  private final AsyncModels asyncModels;
  private final GenAITelemetry telemetry;

  GeminiModels(Models models, AsyncModels asyncModels, GenAITelemetry telemetry) {
    this.models = models;
    this.asyncModels = asyncModels;
    this.telemetry = telemetry;
  }

  /** The wrapped {@code client.models}, for calls that aren't traced. */
  public Models getModels() {
    return models;
  }

  /** The traced {@code client.async.models}. */
  public AsyncGeminiModels async() {
    return new AsyncGeminiModels(asyncModels, telemetry);
  }

  public GenerateContentResponse generateContent(
      String model, String text, GenerateContentConfig config) {
    return generateContent(model, text, config, () -> models.generateContent(model, text, config));
  }

  public GenerateContentResponse generateContent(
      String model, Content content, GenerateContentConfig config) {
    return generateContent(
        model, content, config, () -> models.generateContent(model, content, config));
  }

  public GenerateContentResponse generateContent(
      String model, List<Content> contents, GenerateContentConfig config) {
    return generateContent(
        model, contents, config, () -> models.generateContent(model, contents, config));
  }

  public GeminiResponseStream<GenerateContentResponse> generateContentStream(
      String model, String text, GenerateContentConfig config) {
    return generateContentStream(
        model, text, config, () -> models.generateContentStream(model, text, config));
  }

  public GeminiResponseStream<GenerateContentResponse> generateContentStream(
      String model, Content content, GenerateContentConfig config) {
    return generateContentStream(
        model, content, config, () -> models.generateContentStream(model, content, config));
  }

  public GeminiResponseStream<GenerateContentResponse> generateContentStream(
      String model, List<Content> contents, GenerateContentConfig config) {
    return generateContentStream(
        model, contents, config, () -> models.generateContentStream(model, contents, config));
  }

  private GenerateContentResponse generateContent(
      String model,
      Object contents,
      GenerateContentConfig config,
      Supplier<GenerateContentResponse> call) {
    GenAICaptureOptions capture = telemetry.getCaptureOptions();
    Map<String, Object> params = GeminiAttributes.toPlainRequest(model, contents, config);
    try {
      return telemetry.trace(
          GeminiAttributes.spanName(params),
          GeminiAttributes.requestAttributes(
              GeminiAttributes.SOURCE_GENERATE_CONTENT, params, capture),
          call::get,
          response -> responseAttributes(response, capture));
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable t) {
      // The Gen AI SDK only throws unchecked exceptions.
      throw new IllegalStateException(t);
    }
  }

  private GeminiResponseStream<GenerateContentResponse> generateContentStream(
      String model,
      Object contents,
      GenerateContentConfig config,
      Supplier<ResponseStream<GenerateContentResponse>> call) {
    GenAICaptureOptions capture = telemetry.getCaptureOptions();
    Map<String, Object> params = GeminiAttributes.toPlainRequest(model, contents, config);
    GenAISpan span =
        telemetry.startSpan(
            GeminiAttributes.spanName(params),
            GeminiAttributes.requestAttributes(
                GeminiAttributes.SOURCE_GENERATE_CONTENT_STREAM, params, capture));
    ResponseStream<GenerateContentResponse> stream;
    try (Scope ignored = span.getContext().makeCurrent()) {
      stream = call.get();
    } catch (RuntimeException | Error e) {
      span.fail(e);
      span.end();
      throw e;
    }
    return tracked(stream, span, capture);
  }

  static GeminiResponseStream<GenerateContentResponse> tracked(
      ResponseStream<GenerateContentResponse> stream, GenAISpan span, GenAICaptureOptions capture) {
    return new GeminiResponseStream<GenerateContentResponse>(
        stream,
        new GenAIStreamTracker<GenerateContentResponse>(
            span, chunks -> streamAttributes(chunks, capture)));
  }

  static Map<String, Object> responseAttributes(
      GenerateContentResponse response, GenAICaptureOptions capture) {
    return GeminiAttributes.responseAttributes(map(GeminiAttributes.toPlain(response)), capture);
  }

  private static Map<String, Object> streamAttributes(
      List<GenerateContentResponse> chunks, GenAICaptureOptions capture) {
    List<Map<String, Object>> plain = new ArrayList<Map<String, Object>>();
    for (GenerateContentResponse chunk : chunks) {
      plain.add(map(GeminiAttributes.toPlain(chunk)));
    }
    return GeminiAttributes.responseAttributes(
        GeminiAttributes.aggregateStreamChunks(plain), capture);
  }
}
