package com.statsig.ai.instrumentation.openai;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.statsig.ai.instrumentation.GenAITelemetry;
import com.statsig.ai.instrumentation.openai.handlers.OpenAIServiceHandler;
import com.statsig.ai.instrumentation.openai.telemetry.OpenAIAttributes;
import java.util.Objects;

/**
 * Wraps an openai-java client so its model calls are recorded as spans, like {@code wrapOpenAI} in
 * the Node SDK. See {@link OpenAIServiceHandler} for the traced calls.
 */
public final class OpenAIWrapper {
  private OpenAIWrapper() {}

  /**
   * Wraps {@code client} with the default {@link OpenAIWrapperConfig}, which captures no message
   * content.
   */
  public static OpenAIClient wrap(OpenAIClient client) {
    return wrap(client, (OpenAIWrapperConfig) null);
  }

  /**
   * Wraps {@code client} so its model calls are recorded as spans. {@code client.async()} returns a
   * wrapped async client.
   *
   * @param config what the spans record; null uses the default config, which captures no message
   *     content
   */
  public static OpenAIClient wrap(OpenAIClient client, OpenAIWrapperConfig config) {
    Objects.requireNonNull(client, "client");
    return wrap(client, new GenAITelemetry(OpenAIAttributes.INSTRUMENTATION_NAME, config));
  }

  /**
   * Wraps an async {@code client} with the default {@link OpenAIWrapperConfig}, which captures no
   * message content.
   */
  public static OpenAIClientAsync wrap(OpenAIClientAsync client) {
    return wrap(client, (OpenAIWrapperConfig) null);
  }

  /**
   * Wraps an async {@code client} so its model calls are recorded as spans. A call's span ends when
   * its future completes. {@code client.sync()} returns a wrapped blocking client.
   *
   * @param config what the spans record; null uses the default config, which captures no message
   *     content
   */
  public static OpenAIClientAsync wrap(OpenAIClientAsync client, OpenAIWrapperConfig config) {
    Objects.requireNonNull(client, "client");
    return wrap(client, new GenAITelemetry(OpenAIAttributes.INSTRUMENTATION_NAME, config));
  }

  // Tests pass a GenAITelemetry that reads the statsig::gen_ai events.
  static OpenAIClient wrap(OpenAIClient client, GenAITelemetry telemetry) {
    Objects.requireNonNull(client, "client");
    return OpenAIServiceHandler.wrap(OpenAIClient.class, client, "", telemetry);
  }

  static OpenAIClientAsync wrap(OpenAIClientAsync client, GenAITelemetry telemetry) {
    Objects.requireNonNull(client, "client");
    return OpenAIServiceHandler.wrap(OpenAIClientAsync.class, client, "", telemetry);
  }
}
