package com.statsig.ai.evals;

/**
 * Turns a record's input into the output that the scorers grade. If it throws, the record is an
 * error: the scorers don't run, every score is 0, and Statsig gets {@code "[Error]"} as the output.
 *
 * <p>For a task that returns a {@code CompletableFuture}, use {@link AsyncEvalTask}.
 *
 * @param <I> input type
 * @param <O> output type
 */
@FunctionalInterface
public interface EvalTask<I, O> {
  O run(I input, EvalHooks hooks) throws Exception;
}
