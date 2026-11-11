package com.statsig.ai.evals;

import java.util.concurrent.CompletableFuture;

/**
 * An {@link EvalTask} that returns a {@code CompletableFuture} of the output. A future that
 * completes exceptionally counts as a failed task, like a throw.
 *
 * @param <I> input type
 * @param <O> output type
 */
@FunctionalInterface
public interface AsyncEvalTask<I, O> {
  CompletableFuture<O> run(I input, EvalHooks hooks) throws Exception;
}
