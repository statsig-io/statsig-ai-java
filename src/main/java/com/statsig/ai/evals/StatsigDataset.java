package com.statsig.ai.evals;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Eval data from a Statsig eval dataset, for {@link Eval#builder(String, StatsigDataset)}. Each
 * record keeps the ID of its dataset row and the run names the dataset, so the console compares
 * runs on the dataset row by row.
 *
 * <pre>{@code
 * StatsigDataset<String, String> data =
 *     StatsigDataset.of(
 *         "support_cases",
 *         row ->
 *             EvalDataRecord.of(
 *                 row.getColumns().get("question"), row.getColumns().get("answer"), row.getGroups()));
 * }</pre>
 *
 * @param <I> input type
 * @param <E> expected value type
 */
public final class StatsigDataset<I, E> {
  static final String DEFAULT_ENDPOINT = "https://api.statsig.com/console/v1/eval_datasets";
  static final int PAGE_SIZE = 100;

  private final String name;
  private final Function<StatsigDatasetRow, EvalDataRecord<I, E>> toRecord;

  private StatsigDataset(String name, Function<StatsigDatasetRow, EvalDataRecord<I, E>> toRecord) {
    this.name = name;
    this.toRecord = toRecord;
  }

  /**
   * The eval dataset named {@code name} (its name or ID in the console). {@code toRecord} says
   * which columns hold the input and the expected value. The record it returns gets the row's ID,
   * which replaces any row ID it set. The rows load when the eval runs, with the eval's API key.
   */
  public static <I, E> StatsigDataset<I, E> of(
      String name, Function<StatsigDatasetRow, EvalDataRecord<I, E>> toRecord) {
    return new StatsigDataset<>(
        Objects.requireNonNull(name, "name"), Objects.requireNonNull(toRecord, "toRecord"));
  }

  /** The dataset's name, as given to {@link #of(String, Function)}. */
  public String getName() {
    return name;
  }

  /**
   * Loads every row, a page of {@value #PAGE_SIZE} at a time, until a page comes back short.
   *
   * @throws IllegalStateException if the API answers a page with a non-2xx status
   * @throws UncheckedIOException if a page can't be fetched or read
   */
  List<EvalDataRecord<I, E>> load(String endpoint, String apiKey) {
    List<EvalDataRecord<I, E>> records = new ArrayList<>();
    for (int page = 1; ; page++) {
      List<StatsigDatasetRow> rows = fetchPage(endpoint, apiKey, page);
      for (StatsigDatasetRow row : rows) {
        EvalDataRecord<I, E> record = toRecord.apply(row);
        if (record == null) {
          throw new NullPointerException(
              "[StatsigAI] toRecord returned null for row " + row.getRowID() + " of " + name);
        }
        records.add(record.withRowID(row.getRowID()));
      }
      if (rows.size() < PAGE_SIZE) {
        return records;
      }
    }
  }

  private List<StatsigDatasetRow> fetchPage(String endpoint, String apiKey, int page) {
    String url =
        endpoint
            + "/"
            + EvalResultsSender.encodeURIComponent(name)
            + "/rows?page="
            + page
            + "&limit="
            + PAGE_SIZE;
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) new URL(url).openConnection();
      connection.setRequestMethod("GET");
      connection.setConnectTimeout(EvalResultsSender.CONNECT_TIMEOUT_MS);
      connection.setReadTimeout(EvalResultsSender.READ_TIMEOUT_MS);
      connection.setRequestProperty("STATSIG-API-KEY", apiKey);
      int status = connection.getResponseCode();
      String body = EvalResultsSender.readBody(connection, status);
      if (status < 200 || status >= 300) {
        throw new IllegalStateException(
            failure(
                status
                    + " "
                    + connection.getResponseMessage()
                    + (body.isEmpty() ? "" : " " + EvalResultsSender.truncate(body))));
      }
      return parseRows(EvalResultsSender.MAPPER.readTree(body));
    } catch (IOException e) {
      throw new UncheckedIOException(failure(e.toString()), e);
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  // The response is {data?: [{rowID, columns: [{name, value}], groups?}]}.
  private List<StatsigDatasetRow> parseRows(JsonNode response) {
    JsonNode data = response == null ? null : response.get("data");
    List<StatsigDatasetRow> rows = new ArrayList<>();
    if (data == null || data.isNull()) {
      return rows;
    }
    if (!data.isArray()) {
      throw new IllegalStateException(failure("data is not an array"));
    }
    for (JsonNode row : data) {
      Map<String, String> columns = new LinkedHashMap<>();
      for (JsonNode column : row.path("columns")) {
        String columnName = text(column.get("name"));
        if (columnName != null) {
          columns.put(columnName, text(column.get("value")));
        }
      }
      List<String> groups = new ArrayList<>();
      for (JsonNode group : row.path("groups")) {
        String groupName = text(group);
        if (groupName != null) {
          groups.add(groupName);
        }
      }
      rows.add(
          new StatsigDatasetRow(
              text(row.get("rowID")),
              Collections.unmodifiableMap(columns),
              Collections.unmodifiableList(groups)));
    }
    return rows;
  }

  private String failure(String reason) {
    return "[StatsigAI] Failed to load eval dataset '" + name + "': " + reason;
  }

  // Strings as is, other scalars as their text, and objects or arrays as JSON.
  private static String text(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return null;
    }
    return node.isValueNode() ? node.asText() : node.toString();
  }
}
