package com.statsig.ai.evals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One case of an eval: the input given to the task, and optionally the expected output and a
 * category (one name or a list of names) that the console groups scores by.
 *
 * @param <I> input type
 * @param <E> expected value type
 */
public final class EvalDataRecord<I, E> {
  private final I input;
  private final E expected;
  // As given: a String, an unmodifiable List<String>, or null. Sent in that shape, as Node does.
  private final Object category;
  private final List<String> categories;

  private EvalDataRecord(I input, E expected, Object category, List<String> categories) {
    this.input = input;
    this.expected = expected;
    this.category = category;
    this.categories = categories;
  }

  public static <I, E> EvalDataRecord<I, E> of(I input) {
    return new EvalDataRecord<>(input, null, null, Collections.<String>emptyList());
  }

  /** {@code expected} may be null when there is no expected value. */
  public static <I, E> EvalDataRecord<I, E> of(I input, E expected) {
    return new EvalDataRecord<>(input, expected, null, Collections.<String>emptyList());
  }

  public static <I, E> EvalDataRecord<I, E> of(I input, E expected, String category) {
    Objects.requireNonNull(category, "category");
    return new EvalDataRecord<>(input, expected, category, Collections.singletonList(category));
  }

  public static <I, E> EvalDataRecord<I, E> of(I input, E expected, List<String> categories) {
    Objects.requireNonNull(categories, "categories");
    List<String> copy = new ArrayList<>(categories);
    for (String category : copy) {
      Objects.requireNonNull(category, "categories must not contain null");
    }
    List<String> unmodifiable = Collections.unmodifiableList(copy);
    return new EvalDataRecord<>(input, expected, unmodifiable, unmodifiable);
  }

  public I getInput() {
    return input;
  }

  /** The expected value, or null when the record has none. */
  public E getExpected() {
    return expected;
  }

  /** The record's category as a list: one entry for a single category, empty when it has none. */
  public List<String> getCategories() {
    return categories;
  }

  Object getCategoryAsGiven() {
    return category;
  }
}
