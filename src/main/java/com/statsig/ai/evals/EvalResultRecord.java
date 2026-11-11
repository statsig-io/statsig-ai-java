package com.statsig.ai.evals;

import java.util.List;
import java.util.Map;

/** One record's result: its data, the task's output, and a score from each scorer. */
public final class EvalResultRecord<I, O, E> {
  private final EvalDataRecord<I, E> record;
  private final O output;
  private final boolean error;
  private final Map<String, Double> scores;

  EvalResultRecord(
      EvalDataRecord<I, E> record, O output, boolean error, Map<String, Double> scores) {
    this.record = record;
    this.output = output;
    this.error = error;
    this.scores = scores;
  }

  public I getInput() {
    return record.getInput();
  }

  /**
   * The task's output. Null when the task failed: Statsig gets {@code "[Error]"} as the output
   * instead.
   */
  public O getOutput() {
    return output;
  }

  /** The expected value, or null when the record has none. */
  public E getExpected() {
    return record.getExpected();
  }

  /** The record's category as a list, empty when it has none. */
  public List<String> getCategories() {
    return record.getCategories();
  }

  /** The ID of the record's row in the eval's dataset, or null when it has none. */
  public String getRowID() {
    return record.getRowID();
  }

  /** True when the task failed. Every score is then 0. */
  public boolean isError() {
    return error;
  }

  /** Each scorer's score, by scorer name, in the order the scorers were added. */
  public Map<String, Double> getScores() {
    return scores;
  }
}
