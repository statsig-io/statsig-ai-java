package com.statsig.ai.evals;

import java.util.List;
import java.util.Map;

/**
 * One record's result: its data, the task's output, a score from each scorer, and the task's
 * latency and tokens.
 */
public final class EvalResultRecord<I, O, E> {
  private final EvalDataRecord<I, E> record;
  private final O output;
  private final boolean error;
  private final Map<String, Double> scores;
  private final String traceID;
  private final String spanID;
  private final Long latencyMs;
  private final Long inputTokens;
  private final Long outputTokens;

  EvalResultRecord(
      EvalDataRecord<I, E> record,
      O output,
      boolean error,
      Map<String, Double> scores,
      String traceID,
      String spanID,
      Long latencyMs,
      Long inputTokens,
      Long outputTokens) {
    this.record = record;
    this.output = output;
    this.error = error;
    this.scores = scores;
    this.traceID = traceID;
    this.spanID = spanID;
    this.latencyMs = latencyMs;
    this.inputTokens = inputTokens;
    this.outputTokens = outputTokens;
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

  /**
   * Milliseconds from the task's call until its output was ready, rounded. Scorers aren't part of
   * it. Null when the task failed.
   */
  public Long getLatencyMs() {
    return latencyMs;
  }

  /**
   * The input tokens the task reported with {@link EvalHooks#reportTokens(long, long)}, summed.
   * Null when it reported none or failed.
   */
  public Long getInputTokens() {
    return inputTokens;
  }

  /**
   * The output tokens the task reported with {@link EvalHooks#reportTokens(long, long)}, summed.
   * Null when it reported none or failed.
   */
  public Long getOutputTokens() {
    return outputTokens;
  }
}
