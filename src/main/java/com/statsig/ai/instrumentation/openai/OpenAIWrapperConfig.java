package com.statsig.ai.instrumentation.openai;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Basic configuration object for future OpenAI instrumentation hooks. */
public final class OpenAIWrapperConfig {
  private final Map<String, String> defaultAttributes;

  private OpenAIWrapperConfig(Builder builder) {
    this.defaultAttributes =
        builder.defaultAttributes == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new HashMap<>(builder.defaultAttributes));
  }

  public Map<String, String> getDefaultAttributes() {
    return defaultAttributes;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private Map<String, String> defaultAttributes;

    public Builder setDefaultAttributes(Map<String, String> defaultAttributes) {
      this.defaultAttributes = defaultAttributes;
      return this;
    }

    public OpenAIWrapperConfig build() {
      return new OpenAIWrapperConfig(this);
    }
  }
}
