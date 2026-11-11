package com.statsig.ai.evals;

import java.util.List;
import java.util.Map;

/** The results of an eval run, in the order of its data. */
public final class EvalResult<I, O, E> {
  private final List<EvalResultRecord<I, O, E>> results;
  private final boolean error;
  private final Map<String, Double> summaryScores;

  EvalResult(
      List<EvalResultRecord<I, O, E>> results, boolean error, Map<String, Double> summaryScores) {
    this.results = results;
    this.error = error;
    this.summaryScores = summaryScores;
  }

  public List<EvalResultRecord<I, O, E>> getResults() {
    return results;
  }

  /** True when any record's task failed. */
  public boolean isError() {
    return error;
  }

  /**
   * The summary scores, or null when the eval has no summary scorer or it threw.
   *
   * @see Eval.Builder#summaryScores(java.util.function.Function)
   */
  public Map<String, Double> getSummaryScores() {
    return summaryScores;
  }
}
