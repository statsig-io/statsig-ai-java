package com.statsig.ai.instrumentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Reads plain JSON values: {@code Map<String, Object>}, {@code List<Object>}, strings, numbers,
 * booleans and null, as Jackson parses them into {@code Object}. The provider wrappers turn SDK
 * requests and responses into these, so they can read them the way the Node SDK reads plain
 * objects.
 */
public final class PlainJson {
  private PlainJson() {}

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** {@code value} as a map, or an empty map when it isn't one. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> map(Object value) {
    return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
  }

  /** {@code value} as a list, or null when it isn't one. */
  @SuppressWarnings("unchecked")
  public static List<Object> list(Object value) {
    return value instanceof List ? (List<Object>) value : null;
  }

  /** {@code value} as a string, or null when it isn't one. */
  public static String string(Object value) {
    return value instanceof String ? (String) value : null;
  }

  /** {@code value} as a number, or null when it isn't one. */
  public static Number number(Object value) {
    return value instanceof Number ? (Number) value : null;
  }

  /** Follows {@code keys} through nested maps. Returns null when a key is missing. */
  public static Object get(Object value, String... keys) {
    Object current = value;
    for (String key : keys) {
      if (!(current instanceof Map)) {
        return null;
      }
      current = ((Map<?, ?>) current).get(key);
    }
    return current;
  }

  /** The first value that isn't null, like JavaScript's {@code ??}. */
  public static Object firstNonNull(Object... values) {
    for (Object value : values) {
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  /** Parses {@code json}. Returns {@code json} itself when it isn't valid JSON. */
  public static Object parseOrString(String json) {
    try {
      return MAPPER.readValue(json, Object.class);
    } catch (Exception e) {
      return json;
    }
  }
}
