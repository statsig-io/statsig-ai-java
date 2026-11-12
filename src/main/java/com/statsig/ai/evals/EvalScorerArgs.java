package com.statsig.ai.evals;

import java.util.List;

/** What a scorer grades: the record, plus the output the task gave for it. */
public final class EvalScorerArgs<I, O, E> {
  private final EvalDataRecord<I, E> record;
  private final O output;

  EvalScorerArgs(EvalDataRecord<I, E> record, O output) {
    this.record = record;
    this.output = output;
  }

  public I getInput() {
    return record.getInput();
  }

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
}
