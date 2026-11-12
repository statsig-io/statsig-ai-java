package com.statsig.ai.evals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One case of an eval: the input given to the task, and optionally the expected output, a category
 * (one name or a list of names) that the console groups scores by, and the ID of the record's row
 * in the eval's dataset.
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
  private final String rowID;

  private EvalDataRecord(
      I input, E expected, Object category, List<String> categories, String rowID) {
    this.input = input;
    this.expected = expected;
    this.category = category;
    this.categories = categories;
    this.rowID = rowID;
  }

  public static <I, E> EvalDataRecord<I, E> of(I input) {
    return new EvalDataRecord<>(input, null, null, Collections.<String>emptyList(), null);
  }

  /** {@code expected} may be null when there is no expected value. */
  public static <I, E> EvalDataRecord<I, E> of(I input, E expected) {
    return new EvalDataRecord<>(input, expected, null, Collections.<String>emptyList(), null);
  }

  public static <I, E> EvalDataRecord<I, E> of(I input, E expected, String category) {
    Objects.requireNonNull(category, "category");
    return new EvalDataRecord<>(
        input, expected, category, Collections.singletonList(category), null);
  }

  public static <I, E> EvalDataRecord<I, E> of(I input, E expected, List<String> categories) {
    Objects.requireNonNull(categories, "categories");
    List<String> copy = new ArrayList<>(categories);
    for (String category : copy) {
      Objects.requireNonNull(category, "categories must not contain null");
    }
    List<String> unmodifiable = Collections.unmodifiableList(copy);
    return new EvalDataRecord<>(input, expected, unmodifiable, unmodifiable, null);
  }

  /**
   * A copy of this record with the ID of its row in the eval's dataset, or with no row ID when
   * {@code rowID} is null. Records loaded from a {@link StatsigDataset} already have theirs.
   *
   * @see Eval.Builder#dataset(String)
   */
  public EvalDataRecord<I, E> withRowID(String rowID) {
    return new EvalDataRecord<>(input, expected, category, categories, rowID);
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

  /** The ID of the record's row in the eval's dataset, or null when it has none. */
  public String getRowID() {
    return rowID;
  }

  Object getCategoryAsGiven() {
    return category;
  }
}
