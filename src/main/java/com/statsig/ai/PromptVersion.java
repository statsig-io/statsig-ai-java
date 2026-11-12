package com.statsig.ai;

import com.samskivert.mustache.Mustache;
import com.statsig.DynamicConfig;
import com.statsig.ai.trace.StatsigContextUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class PromptVersion {
  // Like mustache.js in the Node SDK with escaping turned off: values aren't HTML-escaped, a
  // missing or null variable renders as an empty string, and empty strings and zero are falsy in
  // sections.
  private static final Mustache.Compiler TEMPLATES =
      Mustache.compiler()
          .escapeHTML(false)
          .defaultValue("")
          .emptyStringIsFalse(true)
          .zeroIsFalse(true);

  private final Map<String, Object> values;
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
  private final boolean isLiveForUser;

  public PromptVersion(DynamicConfig promtDefinition) {
    this(promtDefinition, false);
  }

  /**
   * @param isLiveForUser {@code true} when this is the live version served to the user, {@code
   *     false} for a candidate.
   */
  public PromptVersion(DynamicConfig promtDefinition, boolean isLiveForUser) {
    Map<String, Object> value = promtDefinition.getValue();
    this.values = value == null ? Collections.<String, Object>emptyMap() : value;
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
    this.isLiveForUser = isLiveForUser;
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

  /** Whether this is the live version served to the user, as opposed to a candidate. */
  public boolean isLiveForUser() {
    return isLiveForUser;
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

  /**
   * Returns the version's value for {@code key}, or {@code fallback} when it's missing, null, or a
   * different type from {@code fallback}. Numbers match numbers, and lists and arrays match each
   * other. A null fallback matches any type.
   */
  public Object getValue(String key, Object fallback) {
    Object value = values.get(key);
    if (value == null || !sameType(value, fallback)) {
      return fallback;
    }
    return value;
  }

  /**
   * Renders the version's prompt messages with {@code params} as Mustache template variables, like
   * the Node SDK. Values aren't HTML-escaped, sections and loops work, and a missing variable
   * renders as an empty string. A version without messages renders one empty system message.
   *
   * <p>Inside {@link Prompt#withVersion} or {@link Prompt#withLive} for this version, the variables
   * are recorded as {@code statsig.llm.prompt_template_variables} on the workflow spans around the
   * call and, when input message capture is on, on model call spans.
   *
   * @param params the template variables, such as a {@code Map} of names to values
   */
  public List<PromptMessage> getPromptMessages(Map<String, ?> params) {
    Map<String, ?> variables = params == null ? Collections.<String, Object>emptyMap() : params;
    StatsigContextUtils.recordPromptTemplateVariables(id, variables);

    List<PromptMessage> messages = new ArrayList<PromptMessage>();
    for (Object prompt : toList(values.get("prompts"))) {
      if (!(prompt instanceof Map)) {
        continue;
      }
      Map<?, ?> message = (Map<?, ?>) prompt;
      Object content = message.get("content");
      String rendered =
          content == null ? "" : TEMPLATES.compile(String.valueOf(content)).execute(variables);
      messages.add(new PromptMessage(String.valueOf(message.get("role")), rendered));
    }
    if (values.get("prompts") == null) {
      messages.add(new PromptMessage("system", ""));
    }
    return messages;
  }

  // ------------------------------------------------------------
  // Private methods
  // ------------------------------------------------------------

  private static List<?> toList(Object value) {
    if (value instanceof List) {
      return (List<?>) value;
    }
    if (value instanceof Object[]) {
      return Arrays.asList((Object[]) value);
    }
    return Collections.emptyList();
  }

  private static boolean sameType(Object value, Object fallback) {
    if (fallback == null) {
      return true;
    }
    if (fallback instanceof Number) {
      return value instanceof Number;
    }
    if (isListLike(fallback)) {
      return isListLike(value);
    }
    if (fallback instanceof Map) {
      return value instanceof Map;
    }
    return fallback.getClass().isInstance(value);
  }

  private static boolean isListLike(Object value) {
    return value instanceof List || value instanceof Object[];
  }

  private String getPromptId(DynamicConfig promtDefinition) {
    String[] parts = promtDefinition.name.split(":");
    if (parts.length > 1) {
      return parts[1];
    }
    return "";
  }
}
