package com.statsig.ai.instrumentation.gemini;

import com.google.genai.AsyncModels;
import com.google.genai.ResponseStream;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.statsig.ai.instrumentation.GenAISpan;
import com.statsig.ai.instrumentation.GenAITelemetry;
import com.statsig.ai.instrumentation.openai.GenAICaptureOptions;
import io.opentelemetry.context.Scope;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * A traced {@code client.async.models}, from {@link GeminiModels#async()}. A {@code
 * generateContent} call's span ends when its future completes, and the returned future completes
 * after that. A {@code generateContentStream} call's span ends like {@link GeminiResponseStream}'s.
 * Use {@link #getModels()} for other calls.
 */
public final class AsyncGeminiModels {
  private final AsyncModels models;
  private final GenAITelemetry telemetry;

  AsyncGeminiModels(AsyncModels models, GenAITelemetry telemetry) {
    this.models = models;
    this.telemetry = telemetry;
  }

  /** The wrapped {@code client.async.models}, for calls that aren't traced. */
  public AsyncModels getModels() {
    return models;
  }

  public CompletableFuture<GenerateContentResponse> generateContent(
      String model, String text, GenerateContentConfig config) {
    return generateContent(model, text, config, () -> models.generateContent(model, text, config));
  }

  public CompletableFuture<GenerateContentResponse> generateContent(
      String model, Content content, GenerateContentConfig config) {
    return generateContent(
        model, content, config, () -> models.generateContent(model, content, config));
  }

  public CompletableFuture<GenerateContentResponse> generateContent(
      String model, List<Content> contents, GenerateContentConfig config) {
    return generateContent(
        model, contents, config, () -> models.generateContent(model, contents, config));
  }

  public CompletableFuture<GeminiResponseStream<GenerateContentResponse>> generateContentStream(
      String model, String text, GenerateContentConfig config) {
    return generateContentStream(
        model, text, config, () -> models.generateContentStream(model, text, config));
  }

  public CompletableFuture<GeminiResponseStream<GenerateContentResponse>> generateContentStream(
      String model, Content content, GenerateContentConfig config) {
    return generateContentStream(
        model, content, config, () -> models.generateContentStream(model, content, config));
  }

  public CompletableFuture<GeminiResponseStream<GenerateContentResponse>> generateContentStream(
      String model, List<Content> contents, GenerateContentConfig config) {
    return generateContentStream(
        model, contents, config, () -> models.generateContentStream(model, contents, config));
  }

  private CompletableFuture<GenerateContentResponse> generateContent(
      String model,
      Object contents,
      GenerateContentConfig config,
      Supplier<CompletableFuture<GenerateContentResponse>> call) {
    GenAICaptureOptions capture = telemetry.getCaptureOptions();
    Map<String, Object> params = GeminiAttributes.toPlainRequest(model, contents, config);
    try {
      return telemetry.traceAsync(
          GeminiAttributes.spanName(params),
          GeminiAttributes.requestAttributes(
              GeminiAttributes.SOURCE_GENERATE_CONTENT, params, capture),
          call::get,
          response -> GeminiModels.responseAttributes(response, capture));
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable t) {
      throw new CompletionException(t);
    }
  }

  private CompletableFuture<GeminiResponseStream<GenerateContentResponse>> generateContentStream(
      String model,
      Object contents,
      GenerateContentConfig config,
      Supplier<CompletableFuture<ResponseStream<GenerateContentResponse>>> call) {
    GenAICaptureOptions capture = telemetry.getCaptureOptions();
    Map<String, Object> params = GeminiAttributes.toPlainRequest(model, contents, config);
    GenAISpan span =
        telemetry.startSpan(
            GeminiAttributes.spanName(params),
            GeminiAttributes.requestAttributes(
                GeminiAttributes.SOURCE_GENERATE_CONTENT_STREAM, params, capture));
    CompletableFuture<ResponseStream<GenerateContentResponse>> future;
    try (Scope ignored = span.getContext().makeCurrent()) {
      future = call.get();
    } catch (RuntimeException | Error e) {
      span.fail(e);
      span.end();
      throw e;
    }
    return future.handle(
        (stream, error) -> {
          if (error != null) {
            Throwable cause =
                error instanceof CompletionException && error.getCause() != null
                    ? error.getCause()
                    : error;
            span.fail(cause);
            span.end();
            throw error instanceof CompletionException
                ? (CompletionException) error
                : new CompletionException(error);
          }
          return GeminiModels.tracked(stream, span, capture);
        });
  }
}
