package com.statsig.ai.evals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the send_results payload (Node's {@code EvalResultsPayload}) and posts it. */
final class EvalResultsSender {
  static final String DEFAULT_ENDPOINT = "https://api.statsig.com/console/v1/evals/send_results";
  static final String ERROR_OUTPUT = "[Error]";

  private static final int CONNECT_TIMEOUT_MS = 10_000;
  private static final int READ_TIMEOUT_MS = 30_000;
  private static final int MAX_LOGGED_BODY_CHARS = 500;

  // Like JSON.stringify, an object with no properties is "{}" rather than an error.
  private static final ObjectMapper MAPPER =
      new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

  private EvalResultsSender() {}

  /** One record's entry in {@code results}. */
  static final class SentRecord {
    final EvalDataRecord<?, ?> record;
    final Object output;
    final boolean error;
    final Map<String, ScoreWithMetadata> scores;

    SentRecord(
        EvalDataRecord<?, ?> record,
        Object output,
        boolean error,
        Map<String, ScoreWithMetadata> scores) {
      this.record = record;
      this.output = output;
      this.error = error;
      this.scores = scores;
    }
  }

  /** Posts the results. A failure is logged, never thrown, as in Node's sendEvalResults. */
  static void send(
      String endpoint,
      String name,
      String apiKey,
      List<SentRecord> records,
      String evalRunName,
      Map<String, Double> summaryScores,
      Map<String, Object> parameters) {
    try {
      if (summaryScores != null) {
        validateSummary(summaryScores);
      }
      byte[] body =
          MAPPER.writeValueAsBytes(payload(records, evalRunName, summaryScores, parameters));
      String url = endpoint + "/" + encodeURIComponent(name);
      HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
      try {
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("STATSIG-API-KEY", apiKey);
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = connection.getOutputStream()) {
          out.write(body);
        }
        int status = connection.getResponseCode();
        String responseBody = readBody(connection, status);
        if (status >= 200 && status < 300) {
          System.out.println(
              "[StatsigAI] Sent eval results ("
                  + records.size()
                  + " records): "
                  + connection.getResponseMessage());
        } else {
          System.err.println(
              "[StatsigAI] Failed to send eval results: "
                  + status
                  + " "
                  + connection.getResponseMessage()
                  + (responseBody.isEmpty() ? "" : " " + truncate(responseBody)));
        }
      } finally {
        connection.disconnect();
      }
    } catch (Exception e) {
      System.err.println("[StatsigAI] Error sending eval results: " + e);
    }
  }

  private static Map<String, Object> payload(
      List<SentRecord> records,
      String evalRunName,
      Map<String, Double> summaryScores,
      Map<String, Object> parameters) {
    List<Map<String, Object>> results = new ArrayList<>();
    for (SentRecord record : records) {
      results.add(serializeRecord(record));
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("results", results);
    if (evalRunName != null) {
      payload.put("name", evalRunName);
    }
    if (summaryScores != null) {
      payload.put("summaryScores", summaryScores);
    }
    Map<String, String> serializedParameters = serializeParameters(parameters);
    if (serializedParameters != null) {
      payload.put("parameters", serializedParameters);
    }
    return payload;
  }

  // The API takes only strings for the input, output, expected value and score metadata values,
  // so other values (such as an object input or a numeric confidence) are sent as JSON. A Java
  // null is treated like Node's undefined: an empty input or output, and no expected value or
  // metadata entry.
  private static Map<String, Object> serializeRecord(SentRecord sent) {
    EvalDataRecord<?, ?> record = sent.record;
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("input", orEmpty(stringify(record.getInput())));
    result.put("output", sent.error ? ERROR_OUTPUT : orEmpty(stringify(sent.output)));
    if (record.getExpected() != null) {
      result.put("expected", stringify(record.getExpected()));
    }
    if (record.getCategoryAsGiven() != null) {
      result.put("category", record.getCategoryAsGiven());
    }
    result.put("error", sent.error);
    Map<String, Object> scores = new LinkedHashMap<>();
    for (Map.Entry<String, ScoreWithMetadata> entry : sent.scores.entrySet()) {
      ScoreWithMetadata score = entry.getValue();
      Map<String, Object> serialized = new LinkedHashMap<>();
      serialized.put("score", score.getScore());
      if (score.getMetadata() != null) {
        serialized.put("metadata", stringifyValues(score.getMetadata()));
      }
      scores.put(entry.getKey(), serialized);
    }
    result.put("scores", scores);
    return result;
  }

  // Parameter values that aren't strings are sent as JSON, like Node's serializeParameters.
  private static Map<String, String> serializeParameters(Map<String, Object> parameters) {
    if (parameters == null || parameters.isEmpty()) {
      return null;
    }
    return stringifyValues(parameters);
  }

  private static Map<String, String> stringifyValues(Map<String, ?> values) {
    Map<String, String> serialized = new LinkedHashMap<>();
    for (Map.Entry<String, ?> entry : values.entrySet()) {
      String value = stringify(entry.getValue());
      if (entry.getKey() != null && value != null) {
        serialized.put(entry.getKey(), value);
      }
    }
    return serialized;
  }

  /** A string as is, null as null, and anything else as JSON (or its toString if that fails). */
  static String stringify(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof String) {
      return (String) value;
    }
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      return String.valueOf(value);
    }
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  private static void validateSummary(Map<String, Double> summaryScores) {
    for (Map.Entry<String, Double> entry : summaryScores.entrySet()) {
      if (entry.getKey() == null) {
        throw new IllegalArgumentException("summary keys must be strings");
      }
      Double value = entry.getValue();
      if (value == null || value.isNaN() || value.isInfinite()) {
        throw new IllegalArgumentException(
            "summary values must be finite numbers. Invalid value for key \""
                + entry.getKey()
                + "\": "
                + value);
      }
    }
  }

  /** Encodes like JavaScript's encodeURIComponent, as Node does for the eval name. */
  static String encodeURIComponent(String value) {
    try {
      return URLEncoder.encode(value, "UTF-8")
          .replace("+", "%20")
          .replace("%21", "!")
          .replace("%27", "'")
          .replace("%28", "(")
          .replace("%29", ")")
          .replace("%7E", "~");
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String readBody(HttpURLConnection connection, int status) throws IOException {
    InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
    if (stream == null) {
      return "";
    }
    try (InputStream in = stream) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  private static String truncate(String value) {
    return value.length() <= MAX_LOGGED_BODY_CHARS
        ? value
        : value.substring(0, MAX_LOGGED_BODY_CHARS) + "...";
  }
}
