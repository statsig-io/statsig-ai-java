package com.statsig.ai.instrumentation.openai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for {@link OpenAIWrapper}: custom span attributes, which message content to
 * capture, and how long a JSON attribute can be.
 */
public final class OpenAIWrapperConfig {
  /** The default {@link #getMaxJsonChars()}, as in the Node SDK. */
  public static final int DEFAULT_MAX_JSON_CHARS = 40_000;

  private final Map<String, Object> customAttributes;
  private final GenAICaptureOptions captureOptions;
  private final int maxJsonChars;

  private OpenAIWrapperConfig(Builder builder) {
    this.customAttributes =
        builder.customAttributes == null
            ? Collections.<String, Object>emptyMap()
            : Collections.unmodifiableMap(
                new LinkedHashMap<String, Object>(builder.customAttributes));
    this.captureOptions =
        builder.captureOptions != null
            ? builder.captureOptions
            : GenAICaptureOptions.builder().build();
    this.maxJsonChars = builder.maxJsonChars > 0 ? builder.maxJsonChars : DEFAULT_MAX_JSON_CHARS;
  }

  /**
   * Attributes added to every model call span. The wrapper's own attributes win when a key is the
   * same.
   */
  public Map<String, Object> getCustomAttributes() {
    return customAttributes;
  }

  /** Which message content model call spans record. By default, none. */
  public GenAICaptureOptions getCaptureOptions() {
    return captureOptions;
  }

  /**
   * The longest a JSON attribute, such as {@code gen_ai.input.messages}, can be before it's
   * truncated. Defaults to {@value #DEFAULT_MAX_JSON_CHARS}.
   */
  public int getMaxJsonChars() {
    return maxJsonChars;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private Map<String, ?> customAttributes;
    private GenAICaptureOptions captureOptions;
    private int maxJsonChars;

    /**
     * Sets attributes to add to every model call span. Values can be strings, booleans, numbers or
     * lists of them; anything else is recorded as JSON.
     */
    public Builder setCustomAttributes(Map<String, ?> customAttributes) {
      this.customAttributes = customAttributes;
      return this;
    }

    /** Sets which message content to capture. By default, none is. */
    public Builder setCaptureOptions(GenAICaptureOptions captureOptions) {
      this.captureOptions = captureOptions;
      return this;
    }

    /**
     * Sets the longest a JSON attribute can be before it's truncated. Zero or less uses {@value
     * OpenAIWrapperConfig#DEFAULT_MAX_JSON_CHARS}.
     */
    public Builder setMaxJsonChars(int maxJsonChars) {
      this.maxJsonChars = maxJsonChars;
      return this;
    }

    public OpenAIWrapperConfig build() {
      return new OpenAIWrapperConfig(this);
    }
  }
}
