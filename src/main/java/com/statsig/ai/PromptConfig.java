package com.statsig.ai;

import com.statsig.DynamicConfig;
import java.util.Map;

public class PromptConfig {
  private final String name;
  private final double temperature;
  private final int maxTokens;
  private final double topP;
  private final double frequencyPenalty;
  private final double presencePenalty;
  private final String provider;
  private final String model;
  private final Map<String, Object> workflowBody;
  private final String evalModel;
  private final String type;
  private final String promptName; // aiConfigName
  private final String id;

  public PromptConfig(DynamicConfig promtDefinition) {
    this.name = promtDefinition.getString("name", "");
    this.temperature = promtDefinition.getDouble("temperature", Double.NaN);
    this.maxTokens = promtDefinition.getInt("maxTokens", Integer.MIN_VALUE);
    this.topP = promtDefinition.getDouble("topP", Double.NaN);
    this.frequencyPenalty = promtDefinition.getDouble("frequencyPenalty", Double.NaN);
    this.presencePenalty = promtDefinition.getDouble("presencePenalty", Double.NaN);
    this.provider = promtDefinition.getString("provider", "");
    this.model = promtDefinition.getString("model", "");
    this.workflowBody = promtDefinition.getMap("workflowBody", null);
    this.evalModel = promtDefinition.getString("evalModel", "");
    this.type = promtDefinition.getString("type", "");
    this.promptName = promtDefinition.getString("aiConfigName", "");
    this.id = getPromptId(promtDefinition);
  }

  // ------------------------------------------------------------
  // Public getters (no fallback)
  // ------------------------------------------------------------

  public String getType() {
    return type;
  }

  public String getPromptName() {
    return promptName;
  }

  public String getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  // ------------------------------------------------------------
  // Public methods (getters with fallbacks)
  // ------------------------------------------------------------

  public double getTemperature() {
    return Double.isNaN(temperature) ? 0.0 : temperature;
  }

  public double getTemperature(double fallback) {
    return Double.isNaN(temperature) ? fallback : temperature;
  }

  public int getMaxTokens() {
    return maxTokens != Integer.MIN_VALUE ? maxTokens : 0;
  }

  public int getMaxTokens(int fallback) {
    return maxTokens != Integer.MIN_VALUE ? maxTokens : fallback;
  }

  public double getTopP() {
    return Double.isNaN(topP) ? 0.0 : topP;
  }

  public double getTopP(double fallback) {
    return Double.isNaN(topP) ? fallback : topP;
  }

  public double getFrequencyPenalty() {
    return Double.isNaN(frequencyPenalty) ? 0.0 : frequencyPenalty;
  }

  public double getFrequencyPenalty(double fallback) {
    return Double.isNaN(frequencyPenalty) ? fallback : frequencyPenalty;
  }

  public double getPresencePenalty() {
    return Double.isNaN(presencePenalty) ? 0.0 : presencePenalty;
  }

  public double getPresencePenalty(double fallback) {
    return Double.isNaN(presencePenalty) ? fallback : presencePenalty;
  }

  public String getProvider() {
    return provider;
  }

  public String getProvider(String fallback) {
    return provider != null && !provider.isEmpty() ? provider : fallback;
  }

  public String getModel() {
    return model;
  }

  public String getModel(String fallback) {
    return model != null && !model.isEmpty() ? model : fallback;
  }

  public Map<String, Object> getWorkflowBody() {
    return workflowBody;
  }

  public Map<String, Object> getWorkflowBody(Map<String, Object> fallback) {
    return workflowBody != null ? workflowBody : fallback;
  }

  public String getEvalModel() {
    return evalModel;
  }

  public String getEvalModel(String fallback) {
    return evalModel != null && !evalModel.isEmpty() ? evalModel : fallback;
  }

  // ------------------------------------------------------------
  // Private methods
  // ------------------------------------------------------------

  private String getPromptId(DynamicConfig promtDefinition) {
    String[] parts = promtDefinition.name.split(":");
    if (parts.length > 1) {
      return parts[1];
    }
    return "";
  }
}
