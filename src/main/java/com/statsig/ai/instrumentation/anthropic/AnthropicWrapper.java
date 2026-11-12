package com.statsig.ai.instrumentation.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientAsync;
import com.statsig.ai.instrumentation.GenAITelemetry;
import com.statsig.ai.instrumentation.openai.OpenAIWrapperConfig;
import java.util.Objects;

/**
 * Wraps an Anthropic Java SDK client so its {@code messages().create} and {@code createStreaming}
 * calls are recorded as model call spans, like {@code wrapAnthropic} in the Node SDK. It takes the
 * same {@link OpenAIWrapperConfig} as {@code OpenAIWrapper}, as Node does, so message content is
 * captured only when you opt in.
 *
 * <p>The Anthropic SDK ({@code com.anthropic:anthropic-java}) isn't a dependency of this SDK, so
 * add it to your app to use this wrapper.
 */
public final class AnthropicWrapper {
  private AnthropicWrapper() {}

  /** Wraps {@code client} with the default config, which captures no message content. */
  public static AnthropicClient wrap(AnthropicClient client) {
    return wrap(client, (OpenAIWrapperConfig) null);
  }

  /**
   * Wraps {@code client}. {@code client.async()} returns a wrapped async client.
   *
   * @param config what the spans record; null uses the default config, which captures no message
   *     content
   */
  public static AnthropicClient wrap(AnthropicClient client, OpenAIWrapperConfig config) {
    Objects.requireNonNull(client, "client");
    return wrap(client, new GenAITelemetry(AnthropicAttributes.INSTRUMENTATION_NAME, config));
  }

  /** Wraps an async {@code client} with the default config, which captures no message content. */
  public static AnthropicClientAsync wrap(AnthropicClientAsync client) {
    return wrap(client, (OpenAIWrapperConfig) null);
  }

  /**
   * Wraps an async {@code client}. A call's span ends when its future completes. {@code
   * client.sync()} returns a wrapped blocking client.
   *
   * @param config what the spans record; null uses the default config, which captures no message
   *     content
   */
  public static AnthropicClientAsync wrap(AnthropicClientAsync client, OpenAIWrapperConfig config) {
    Objects.requireNonNull(client, "client");
    return wrap(client, new GenAITelemetry(AnthropicAttributes.INSTRUMENTATION_NAME, config));
  }

  // Tests pass a GenAITelemetry that reads the statsig::gen_ai events.
  static AnthropicClient wrap(AnthropicClient client, GenAITelemetry telemetry) {
    Objects.requireNonNull(client, "client");
    return AnthropicServiceHandler.wrap(AnthropicClient.class, client, "", telemetry);
  }

  static AnthropicClientAsync wrap(AnthropicClientAsync client, GenAITelemetry telemetry) {
    Objects.requireNonNull(client, "client");
    return AnthropicServiceHandler.wrap(AnthropicClientAsync.class, client, "", telemetry);
  }
}
