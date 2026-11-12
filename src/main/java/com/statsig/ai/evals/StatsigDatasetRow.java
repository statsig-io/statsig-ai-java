package com.statsig.ai.evals;

import java.util.List;
import java.util.Map;

/** One row of a Statsig eval dataset, as given to {@link StatsigDataset}'s {@code toRecord}. */
public final class StatsigDatasetRow {
  private final String rowID;
  private final Map<String, String> columns;
  private final List<String> groups;

  StatsigDatasetRow(String rowID, Map<String, String> columns, List<String> groups) {
    this.rowID = rowID;
    this.columns = columns;
    this.groups = groups;
  }

  /** The row's ID in the dataset. */
  public String getRowID() {
    return rowID;
  }

  /** The row's cell values by column name, in the dataset's column order. */
  public Map<String, String> getColumns() {
    return columns;
  }

  /** The row's groups, empty when it has none. */
  public List<String> getGroups() {
    return groups;
  }
}
