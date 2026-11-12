package com.statsig.ai.evals;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What a task gets besides its input: the eval's parameters and the record's category, and a way to
 * report the tokens it used. Each record gets its own.
 */
public final class EvalHooks {
  private final Map<String, Object> parameters;
  private final List<String> categories;
  // Null until the task reports tokens.
  private final AtomicReference<TokenTotals> tokens = new AtomicReference<>();

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

  /**
   * Reports the tokens the task used to produce its output. Each call adds to the record's totals,
   * so a multi-step task can call it once per model call or once with its total. The totals are
   * sent with the record's result as {@code inputTokens} and {@code outputTokens}. A record whose
   * task never calls this, or fails, sends no tokens.
   *
   * <p>Safe to call from any thread, such as the executor an async task's model calls complete on.
   * Calls after the task's output is ready don't count. A call with a negative count is ignored and
   * logs a warning.
   *
   * @param inputTokens tokens sent to the model, such as the prompt
   * @param outputTokens tokens the model generated
   */
  public void reportTokens(long inputTokens, long outputTokens) {
    if (inputTokens < 0 || outputTokens < 0) {
      System.err.println(
          "[StatsigAI] reportTokens() needs inputTokens and outputTokens of at least 0. Ignoring: "
              + inputTokens
              + ", "
              + outputTokens);
      return;
    }
    tokens.updateAndGet(
        totals ->
            totals == null
                ? new TokenTotals(inputTokens, outputTokens)
                : new TokenTotals(
                    totals.inputTokens + inputTokens, totals.outputTokens + outputTokens));
  }

  /** The totals reported so far, or null when the task reported none. */
  TokenTotals getTokenTotals() {
    return tokens.get();
  }

  static final class TokenTotals {
    final long inputTokens;
    final long outputTokens;

    TokenTotals(long inputTokens, long outputTokens) {
      this.inputTokens = inputTokens;
      this.outputTokens = outputTokens;
    }
  }
}
