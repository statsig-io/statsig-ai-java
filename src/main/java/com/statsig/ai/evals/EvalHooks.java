package com.statsig.ai.evals;

import java.util.List;
import java.util.Map;

/** What a task gets besides its input: the eval's parameters and the record's category. */
public final class EvalHooks {
  private final Map<String, Object> parameters;
  private final List<String> categories;

  EvalHooks(Map<String, Object> parameters, List<String> categories) {
    this.parameters = parameters;
    this.categories = categories;
  }

  /** The eval's parameters, by name. Empty when it has none. */
  public Map<String, Object> getParameters() {
    return parameters;
  }

  /**
   * The parameter named {@code name}, or null when there is none. The value is cast to the type the
   * caller expects, so {@code String model = hooks.getParameter("model");} works.
   */
  @SuppressWarnings("unchecked")
  public <T> T getParameter(String name) {
    return (T) parameters.get(name);
  }

  /** The record's category as a list, empty when it has none. */
  public List<String> getCategories() {
    return categories;
  }
}
