package com.statsig.ai.trace.options;

import com.statsig.StatsigUser;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Options for a span from {@code GenAISpans.wrap} or {@code GenAISpans.startWorkflow}. Start with
 * {@link #workflow(String)} or {@link #tool(String)}, then call {@code build()}.
 */
public final class WrapOptions {

  /** The kind of span. */
  public enum Type {
    /** An {@code invoke_workflow} span, such as an agent turn. */
    WORKFLOW,
    /** An {@code execute_tool} span for one tool call. */
    TOOL
  }

  private final Type type;
  private final String name;
  private final String toolType;
  private final Map<String, Object> attributes;
  private final StatsigUser user;
  private final String activityID;

  private WrapOptions(Builder<?> builder) {
    this.type = builder.type;
    this.name = builder.name;
    this.toolType = builder.toolType;
    this.attributes =
        Collections.unmodifiableMap(new LinkedHashMap<String, Object>(builder.attributes));
    this.user = builder.user;
    this.activityID = builder.activityID;
  }

  /**
   * Starts options for an {@code invoke_workflow} span, such as an agent turn. The name is recorded
   * as {@code statsig.gen_ai.workflow.name}.
   */
  public static WorkflowBuilder workflow(String name) {
    return new WorkflowBuilder(name);
  }

  /**
   * Starts options for an {@code execute_tool} span. The name is recorded as {@code
   * statsig.gen_ai.tool.name}.
   */
  public static ToolBuilder tool(String name) {
    return new ToolBuilder(name);
  }

  public Type getType() {
    return type;
  }

  public String getName() {
    return name;
  }

  /** The tool type, or null when it isn't set. Always null for a workflow. */
  public String getToolType() {
    return toolType;
  }

  /** The custom span attributes. Empty when none are set. */
  public Map<String, Object> getAttributes() {
    return attributes;
  }

  /** The user, or null when it isn't set. */
  public StatsigUser getUser() {
    return user;
  }

  /** The activity ID, or null when it isn't set. */
  public String getActivityID() {
    return activityID;
  }

  /**
   * Builds {@link WrapOptions}. Get one from {@link WrapOptions#workflow(String)} or {@link
   * WrapOptions#tool(String)}.
   */
  public abstract static class Builder<B extends Builder<B>> {
    private final Type type;
    private final String name;
    private final Map<String, Object> attributes = new LinkedHashMap<String, Object>();
    private StatsigUser user;
    private String activityID;
    String toolType;

    Builder(Type type, String name) {
      this.type = type;
      this.name = Objects.requireNonNull(name, "name");
    }

    abstract B self();

    /**
     * Sets custom attributes for the span and its {@code statsig::gen_ai} event, replacing any set
     * earlier. Strings, booleans, numbers, lists and arrays are recorded as they are, and other
     * values as JSON.
     */
    public B setAttributes(Map<String, ?> attributes) {
      this.attributes.clear();
      if (attributes != null) {
        for (Map.Entry<String, ?> entry : attributes.entrySet()) {
          setAttribute(entry.getKey(), entry.getValue());
        }
      }
      return self();
    }

    /**
     * Sets one custom attribute. See {@link #setAttributes(Map)}. A null value removes it.
     *
     * @throws NullPointerException if {@code key} is null
     */
    public B setAttribute(String key, Object value) {
      Objects.requireNonNull(key, "key");
      if (value == null) {
        this.attributes.remove(key);
      } else {
        this.attributes.put(key, value);
      }
      return self();
    }

    /**
     * Sets the user. The span runs in a Statsig context holding the user's IDs and the activity ID,
     * like {@code StatsigContextUtils.withStatsigContext}, so it and the model call spans inside it
     * get {@code statsig.user_id} and {@code statsig.custom_ids}.
     */
    public B setUser(StatsigUser user) {
      this.user = user;
      return self();
    }

    /**
     * Sets the activity ID, recorded in {@code statsig.custom_ids} as {@code statsig.activity_id}.
     * See {@link #setUser(StatsigUser)}. Null or empty sets none.
     */
    public B setActivityID(String activityID) {
      this.activityID = activityID == null || activityID.isEmpty() ? null : activityID;
      return self();
    }

    public WrapOptions build() {
      return new WrapOptions(this);
    }
  }

  /** Builds options for a workflow span. */
  public static final class WorkflowBuilder extends Builder<WorkflowBuilder> {
    private WorkflowBuilder(String name) {
      super(Type.WORKFLOW, name);
    }

    @Override
    WorkflowBuilder self() {
      return this;
    }
  }

  /** Builds options for a tool span. */
  public static final class ToolBuilder extends Builder<ToolBuilder> {
    private ToolBuilder(String name) {
      super(Type.TOOL, name);
    }

    /**
     * Sets the tool's type, recorded as {@code gen_ai.tool.type}, for example {@code function} or
     * {@code retrieval}. Null or empty sets none.
     */
    public ToolBuilder setToolType(String toolType) {
      this.toolType = toolType == null || toolType.isEmpty() ? null : toolType;
      return this;
    }

    @Override
    ToolBuilder self() {
      return this;
    }
  }
}
