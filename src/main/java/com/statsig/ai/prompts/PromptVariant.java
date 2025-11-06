package com.statsig.ai.prompts;

import com.statsig.DynamicConfig;

public class PromptVariant {
  private final String name;
  private final double temperature;
  private final int maxTokens;
  private final double topP;
  private final double frequencyPenalty;
  private final double presencePenalty;
  private final String provider;
  private final String model;
  private final WorkflowBody workflowBody;
  private final String evalModel;
  private final String type;
  private final String aiConfigName;
  private final String id;

  public PromptVariant(DynamicConfig promtDefinition) {
    this.name = promtDefinition.getString("name", "");
    this.temperature = promtDefinition.getDouble("temperature", 0.0);
    this.maxTokens = promtDefinition.getInt("maxTokens", 0);
    this.topP = promtDefinition.getDouble("topP", 0.0);
    this.frequencyPenalty = promtDefinition.getDouble("frequencyPenalty", 0.0);
    this.presencePenalty = promtDefinition.getDouble("presencePenalty", 0.0);
    this.provider = promtDefinition.getString("provider", "");
    this.model = promtDefinition.getString("model", "");
    this.workflowBody =
        new WorkflowBody(
            promtDefinition.getString("workflowBodyType", ""),
            promtDefinition.getString("workflowBodyValue", ""));
    this.evalModel = promtDefinition.getString("evalModel", "");
    this.type = promtDefinition.getString("type", "");
    this.aiConfigName = promtDefinition.getString("aiConfigName", "");
    this.id = getPromptId(promtDefinition);
  }

  private String getPromptId(DynamicConfig promtDefinition) {
    String[] parts = promtDefinition.name.split(":");
    if (parts.length > 1) {
        return parts[1];
    }
    return "";
  }

  public String getName() {
    return name;
  }

  public double getTemperature() {
    return temperature;
  }

  public int getMaxTokens() {
    return maxTokens;
  }

  public double getTopP() {
    return topP;
  }

  public double getFrequencyPenalty() {
    return frequencyPenalty;
  }

  public double getPresencePenalty() {
    return presencePenalty;
  }

  public String getProvider() {
    return provider;
  }

  public String getModel() {
    return model;
  }

  public WorkflowBody getWorkflowBody() {
    return workflowBody;
  }

  public String getEvalModel() {
    return evalModel;
  }

  public String getType() {
    return type;
  }

  public String getAiConfigName() {
    return aiConfigName;
  }

  public String getId() {
    return id;
  }

  public static class WorkflowBody {
    private final String type;
    private final String value;

    public WorkflowBody(String type, String value) {
      this.type = type;
      this.value = value;
    }

    public String getType() {
      return type;
    }

    public String getValue() {
      return value;
    }
  }
}
