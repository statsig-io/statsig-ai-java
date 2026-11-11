package com.statsig.ai.instrumentation.openai;

import com.openai.client.OpenAIClient;
import com.statsig.ai.instrumentation.openai.handlers.ClientHandler;
import java.lang.reflect.Proxy;
import java.util.Objects;

public final class OpenAIWrapper {
  private OpenAIWrapper() {}

  /**
   * Wraps {@code client} with the default {@link OpenAIWrapperConfig}, which captures no message
   * content.
   */
  public static OpenAIClient wrap(OpenAIClient client) {
    return wrap(client, null);
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
    OpenAIWrapperConfig resolved = config == null ? OpenAIWrapperConfig.builder().build() : config;
    return (OpenAIClient)
        Proxy.newProxyInstance(
            client.getClass().getClassLoader(),
            new Class<?>[] {OpenAIClient.class},
            new ClientHandler(client, resolved));
  }
}
