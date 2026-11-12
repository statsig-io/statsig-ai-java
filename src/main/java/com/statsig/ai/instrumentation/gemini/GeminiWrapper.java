package com.statsig.ai.instrumentation.gemini;

import com.google.genai.Client;
import com.statsig.ai.instrumentation.GenAITelemetry;
import com.statsig.ai.instrumentation.openai.OpenAIWrapperConfig;
import java.util.Objects;

/**
 * Traces a Google Gen AI Java SDK client's {@code models.generateContent} and {@code
 * generateContentStream} calls as model call spans, like {@code wrapGemini} in the Node SDK. It
 * takes the same {@link OpenAIWrapperConfig} as {@code OpenAIWrapper}, as Node does, so message
 * content is captured only when you opt in.
 *
 * <p>The Java {@code Client} and its {@code models} are final classes, so they can't be wrapped in
 * place. Call the methods on the returned {@link GeminiModels} instead of {@code client.models}.
 *
 * <p>The Gen AI SDK ({@code com.google.genai:google-genai}) isn't a dependency of this SDK, so add
 * it to your app to use this wrapper.
 */
public final class GeminiWrapper {
  private GeminiWrapper() {}

  /** Returns a traced {@code client.models} with the default config, which captures no content. */
  public static GeminiModels wrap(Client client) {
    return wrap(client, null);
  }

  /**
   * Returns a traced {@code client.models}. Its {@code async()} is a traced {@code
   * client.async.models}.
   *
   * @param config what the spans record; null uses the default config, which captures no message
   *     content
   */
  public static GeminiModels wrap(Client client, OpenAIWrapperConfig config) {
    Objects.requireNonNull(client, "client");
    return new GeminiModels(
        client.models,
        client.async.models,
        new GenAITelemetry(GeminiAttributes.INSTRUMENTATION_NAME, config));
  }
}
