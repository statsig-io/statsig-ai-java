package com.statsig.ai.instrumentation.openai;

/** Controls which OpenAI request/response fields should be captured on spans. */
public final class GenAICaptureOptions {
  private final boolean captureAll;
  private final boolean captureInputMessages;
  private final boolean captureOutputMessages;
  private final boolean captureSystemInstructions;
  private final boolean captureToolDefinitions;

  private GenAICaptureOptions(Builder builder) {
    this.captureAll = builder.captureAll;
    this.captureInputMessages = builder.captureInputMessages;
    this.captureOutputMessages = builder.captureOutputMessages;
    this.captureSystemInstructions = builder.captureSystemInstructions;
    this.captureToolDefinitions = builder.captureToolDefinitions;
  }

  public boolean captureAll() {
    return captureAll;
  }

  public boolean captureInputMessages() {
    return captureAll || captureInputMessages;
  }

  public boolean captureOutputMessages() {
    return captureAll || captureOutputMessages;
  }

  public boolean captureSystemInstructions() {
    return captureAll || captureSystemInstructions;
  }

  public boolean captureToolDefinitions() {
    return captureAll || captureToolDefinitions;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private boolean captureAll = false;
    private boolean captureInputMessages = false;
    private boolean captureOutputMessages = false;
    private boolean captureSystemInstructions = false;
    private boolean captureToolDefinitions = false;

    public Builder captureAll(boolean value) {
      this.captureAll = value;
      return this;
    }

    public Builder captureInputMessages(boolean value) {
      this.captureInputMessages = value;
      return this;
    }

    public Builder captureOutputMessages(boolean value) {
      this.captureOutputMessages = value;
      return this;
    }

    public Builder captureSystemInstructions(boolean value) {
      this.captureSystemInstructions = value;
      return this;
    }

    public Builder captureToolDefinitions(boolean value) {
      this.captureToolDefinitions = value;
      return this;
    }

    public GenAICaptureOptions build() {
      return new GenAICaptureOptions(this);
    }
  }
}
