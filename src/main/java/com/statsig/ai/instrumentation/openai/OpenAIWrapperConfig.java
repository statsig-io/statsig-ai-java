package com.statsig.ai.instrumentation.openai;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Configuration for OpenAIWrapper (custom span attributes, capture options, etc.). */
public final class OpenAIWrapperConfig {
  private final Map<String, String> customSpanAttributes;
  private final GenAICaptureOptions captureOptions;
  private final int maxJsonChars;
  private static final int DEFAULT_MAX_JSON_CHARS = 40_000;

  private OpenAIWrapperConfig(Builder builder) {
    this.customSpanAttributes =
        builder.customSpanAttributes == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new HashMap<>(builder.customSpanAttributes));
    this.captureOptions =
        builder.captureOptions != null
            ? builder.captureOptions
            : GenAICaptureOptions.builder().build();
    this.maxJsonChars = builder.maxJsonChars > 0 ? builder.maxJsonChars : DEFAULT_MAX_JSON_CHARS;
  }

  public Map<String, String> getCustomSpanAttributes() {
    return customSpanAttributes;
  }

  public GenAICaptureOptions getCaptureOptions() {
    return captureOptions;
  }

  public int getMaxJsonChars() {
    return maxJsonChars;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private Map<String, String> customSpanAttributes;
    private GenAICaptureOptions captureOptions;
    private int maxJsonChars;

    public Builder setCustomSpanAttributes(Map<String, String> customSpanAttributes) {
      this.customSpanAttributes = customSpanAttributes;
      return this;
    }

    public Builder setCaptureOptions(GenAICaptureOptions captureOptions) {
      this.captureOptions = captureOptions;
      return this;
    }

    public Builder setMaxJsonChars(int maxJsonChars) {
      this.maxJsonChars = maxJsonChars;
      return this;
    }

    public OpenAIWrapperConfig build() {
      return new OpenAIWrapperConfig(this);
    }
  }
}
