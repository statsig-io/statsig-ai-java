package com.statsig.ai.evals;

import java.util.List;
import java.util.Map;

/** One record's result: its data, the task's output, and a score from each scorer. */
public final class EvalResultRecord<I, O, E> {
  private final EvalDataRecord<I, E> record;
  private final O output;
  private final boolean error;
  private final Map<String, Double> scores;
  private final String traceID;
  private final String spanID;

  EvalResultRecord(
      EvalDataRecord<I, E> record,
      O output,
      boolean error,
      Map<String, Double> scores,
      String traceID,
      String spanID) {
    this.record = record;
    this.output = output;
    this.error = error;
    this.scores = scores;
    this.traceID = traceID;
    this.spanID = spanID;
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

  /** True when the task failed. Every score is then 0. */
  public boolean isError() {
    return error;
  }

  /** Each scorer's score, by scorer name, in the order the scorers were added. */
  public Map<String, Double> getScores() {
    return scores;
  }

  /** The ID of the record's own trace. Null when tracing isn't initialized. */
  public String getTraceID() {
    return traceID;
  }

  /**
   * The ID of the record's root span, which the task's and scorers' spans are under. Null when
   * tracing isn't initialized.
   */
  public String getSpanID() {
    return spanID;
  }
}
