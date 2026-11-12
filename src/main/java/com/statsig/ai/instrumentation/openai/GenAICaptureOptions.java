package com.statsig.ai.instrumentation.openai;

/**
 * Controls which message content model call spans capture. Messages usually hold user input and
 * model output, so by default none is captured.
 *
 * <ul>
 *   <li>{@code captureInputMessages}: {@code gen_ai.input.messages}
 *   <li>{@code captureOutputMessages}: {@code gen_ai.output.messages}
 *   <li>{@code captureSystemInstructions}: the system instructions
 *   <li>{@code captureToolDefinitions}: {@code gen_ai.tool.definitions}
 *   <li>{@code captureAll}: all four
 * </ul>
 */
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
