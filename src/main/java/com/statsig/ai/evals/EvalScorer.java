package com.statsig.ai.evals;

/**
 * Grades a record's output.
 *
 * <p>Return a {@code Number}, a {@code Boolean} (true is 1, false is 0) or a {@link
 * ScoreWithMetadata}, or a {@code CompletionStage} (such as a {@code CompletableFuture}) of one.
 * Anything else, or a throw, scores 0 and logs a warning; the record isn't marked as an error.
 *
 * @param <I> input type
 * @param <O> output type
 * @param <E> expected value type
 */
@FunctionalInterface
public interface EvalScorer<I, O, E> {
  Object score(EvalScorerArgs<I, O, E> args) throws Exception;
}
