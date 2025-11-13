package com.statsig.ai.instrumentation.openai;

import com.openai.client.OpenAIClient;
import java.util.Objects;

/**
 * MVP entry point for wrapping OpenAI clients. Currently acts as a no-op pass-through but validates
 * inputs and reserves extension points for future instrumentation.
 */
public final class OpenAIWrapper {
  private OpenAIWrapper() {}

  public static OpenAIClient wrap(OpenAIClient client) {
    return wrap(client, OpenAIWrapperConfig.builder().build());
  }

  public static OpenAIClient wrap(OpenAIClient client, OpenAIWrapperConfig config) {
    Objects.requireNonNull(client, "client");
    Objects.requireNonNull(config, "config");
    // MVP: no instrumentation yet, simply return the provided client.
    return client;
  }
}
