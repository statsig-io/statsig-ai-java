package com.statsig.ai.instrumentation.openai;

import com.openai.client.OpenAIClient;
import com.statsig.ai.instrumentation.GenAITelemetry;
import com.statsig.ai.instrumentation.openai.handlers.ClientHandler;
import com.statsig.ai.instrumentation.openai.telemetry.OpenAIAttributes;
import java.lang.reflect.Proxy;
import java.util.Objects;

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
   * Wraps {@code client} so each {@code chat().completions().create(...)} call is recorded as a
   * model call span.
   *
   * @param config what the spans record; null uses the default config, which captures no message
   *     content
   */
  public static OpenAIClient wrap(OpenAIClient client, OpenAIWrapperConfig config) {
    Objects.requireNonNull(client, "client");
    return wrap(client, new GenAITelemetry(OpenAIAttributes.INSTRUMENTATION_NAME, config));
  }

  // Tests pass a GenAITelemetry that reads the statsig::gen_ai events.
  static OpenAIClient wrap(OpenAIClient client, GenAITelemetry telemetry) {
    Objects.requireNonNull(client, "client");
    return (OpenAIClient)
        Proxy.newProxyInstance(
            client.getClass().getClassLoader(),
            new Class<?>[] {OpenAIClient.class},
            new ClientHandler(client, telemetry));
  }
}
