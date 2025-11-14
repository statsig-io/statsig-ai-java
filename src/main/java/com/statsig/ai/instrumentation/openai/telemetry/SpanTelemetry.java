package com.statsig.ai.instrumentation.openai.telemetry;

import com.statsig.Statsig;
import com.statsig.StatsigUser;
import com.statsig.ai.StatsigAI;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.*;
import java.lang.reflect.Array;
import java.util.*;

/**
 * Wraps an OTEL Span with attribute helpers, JSON truncation, exception handling, metadata
 * aggregation, and Statsig event logging.
 */
public final class SpanTelemetry {

  public static final int DEFAULT_MAX_JSON_CHARS = 4096;

  private final Span span;
  private final String spanName;
  private final int maxJsonChars;
  private final long startNano = System.nanoTime();
  private final SpanEventLogger eventLogger;

  private final Map<String, String> metadata = new HashMap<String, String>();
  private boolean ended = false;

  public SpanTelemetry(Span span, String spanName, int maxJsonChars, SpanEventLogger logger) {
    this.span = Objects.requireNonNull(span);
    this.spanName = Objects.requireNonNull(spanName);
    this.maxJsonChars = Math.max(0, maxJsonChars);
    this.eventLogger = logger == null ? SpanEventLogger.noop() : logger;

    metadata.put("span.name", spanName);

    SpanContext ctx = span.getSpanContext();
    metadata.put("span.trace_id", ctx.getTraceId());
    metadata.put("span.span_id", ctx.getSpanId());
  }

  public Span getSpan() {
    return span;
  }

  // ----------------------------------------------------------------------
  // Attribute setters
  // ----------------------------------------------------------------------

  public void setAttribute(String key, Object value) {
    if (key == null || value == null) return;

    if (value instanceof String) {
      String s = (String) value;
      span.setAttribute(key, s);
      metadata.put(key, s);
      return;
    }

    if (value instanceof Boolean) {
      Boolean b = (Boolean) value;
      span.setAttribute(key, b);
      metadata.put(key, b.toString());
      return;
    }

    if (value instanceof Number) {
      Number n = (Number) value;
      if (n instanceof Double || n instanceof Float) {
        span.setAttribute(key, n.doubleValue());
      } else {
        span.setAttribute(key, n.longValue());
      }
      metadata.put(key, n.toString());
      return;
    }

    if (value instanceof List) {
      List<?> list = (List<?>) value;
      List<String> strs = new ArrayList<String>();
      for (Object o : list) {
        if (o != null) strs.add(String.valueOf(o));
      }
      span.setAttribute(AttributeKey.stringArrayKey(key), strs);
      metadata.put(key, safeStringify(strs));
      return;
    }

    if (value.getClass().isArray()) {
      int len = Array.getLength(value);
      List<Object> arr = new ArrayList<Object>(len);
      for (int i = 0; i < len; i++) {
        arr.add(Array.get(value, i));
      }
      setAttribute(key, arr);
      return;
    }

    setJsonAttribute(key, value);
  }

  public void setJsonAttribute(String key, Object value) {
    if (key == null) return;

    String json = safeStringify(value);
    boolean truncated = json.length() > maxJsonChars;

    String rendered = truncated ? json.substring(0, maxJsonChars) + "…(truncated)" : json;

    span.setAttribute(key, rendered);
    metadata.put(key, rendered);

    if (truncated) {
      span.setAttribute(key + "_len", json.length());
    }
  }

  // ----------------------------------------------------------------------
  // Status & errors
  // ----------------------------------------------------------------------

  public void setStatus(StatusCode code) {
    setStatus(code, null);
  }

  public void setStatus(StatusCode code, String message) {
    if (code == null) return;

    if (message == null) {
      span.setStatus(code);
    } else {
      span.setStatus(code, message);
    }

    metadata.put("span.status_code", code.name());
    metadata.put("span.status_code_value", String.valueOf(code.ordinal()));
    if (message != null) metadata.put("span.status_message", message);
  }

  public void recordException(Throwable error) {
    if (error == null) return;

    span.recordException(error);

    metadata.put("exception.type", error.getClass().getName());
    if (error.getMessage() != null) metadata.put("exception.message", error.getMessage());
  }

  public void fail(Throwable error) {
    recordException(error);
    String msg = error == null ? null : error.getMessage();
    if (msg == null && error != null) msg = error.toString();
    setStatus(StatusCode.ERROR, msg);
  }

  // ----------------------------------------------------------------------
  // Timing
  // ----------------------------------------------------------------------

  public void recordTimeToFirstToken() {
    long ms = (System.nanoTime() - startNano) / 1_000_000;
    double sec = ms / 1000.0;
    setAttribute("gen_ai.server.time_to_first_token", sec);
    setAttribute("gen_ai.server.time_to_first_token_ms", ms);
  }

  // ----------------------------------------------------------------------
  // End
  // ----------------------------------------------------------------------

  public void end() {
    if (ended) return;
    ended = true;

    span.end();

    eventLogger.log(spanName, Collections.unmodifiableMap(new HashMap<String, String>(metadata)));
  }

  // ----------------------------------------------------------------------
  // Helpers
  // ----------------------------------------------------------------------

  private static String safeStringify(Object v) {
    try {
      return JsonStringifier.stringify(v);
    } catch (Throwable t) {
      return String.valueOf(v);
    }
  }

  // ----------------------------------------------------------------------
  // Statsig event logger
  // ----------------------------------------------------------------------

  public interface SpanEventLogger {
    void log(String name, Map<String, String> metadata);

    static SpanEventLogger noop() {
      return new SpanEventLogger() {
        public void log(String n, Map<String, String> m) {}
      };
    }
  }

  public static SpanEventLogger statsigEventLogger() {
    return StatsigEventLogger.INSTANCE;
  }

  private static final class StatsigEventLogger implements SpanEventLogger {
    static final StatsigEventLogger INSTANCE = new StatsigEventLogger();

    private static final String EVENT_NAME = "statsig::gen_ai";
    private static final StatsigUser DEFAULT_USER =
        new StatsigUser.Builder().setUserID("statsig-ai-wrapper").build();

    private volatile boolean warned = false;

    public void log(String spanName, Map<String, String> metadata) {
      Statsig statsig = getStatsig();
      if (statsig == null) {
        warnOnce();
        return;
      }
      statsig.logEvent(DEFAULT_USER, EVENT_NAME, spanName, metadata);
    }

    private Statsig getStatsig() {
      if (!StatsigAI.hasShared()) return null;
      StatsigAI ai = StatsigAI.shared();
      return ai == null ? null : ai.getStatsig();
    }

    private void warnOnce() {
      if (warned) return;
      synchronized (this) {
        if (warned) return;
        warned = true;
        System.err.println("[StatsigAI] No shared StatsigAI instance. Call StatsigAI.newShared().");
      }
    }
  }

  // ----------------------------------------------------------------------
  // JSON Stringifier (Java 8 safe)
  // ----------------------------------------------------------------------

  private static final class JsonStringifier {
    private static final int MAX_DEPTH = 8;

    static String stringify(Object v) {
      StringBuilder sb = new StringBuilder();
      IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<Object, Boolean>();
      write(v, sb, 0, seen);
      return sb.toString();
    }

    private static void write(
        Object v, StringBuilder sb, int depth, IdentityHashMap<Object, Boolean> seen) {

      if (v == null) {
        sb.append("null");
        return;
      }

      if (depth > MAX_DEPTH) {
        sb.append("\"[[max_depth_exceeded]]\"");
        return;
      }

      if (v instanceof String) {
        escape(sb, (String) v);
        return;
      }

      if (v instanceof Number || v instanceof Boolean) {
        sb.append(String.valueOf(v));
        return;
      }

      if (v instanceof Map) {
        if (seen.put(v, Boolean.TRUE) != null) {
          sb.append("\"[[circular]]\"");
          return;
        }

        sb.append('{');
        boolean first = true;
        Map<?, ?> map = (Map<?, ?>) v;

        for (Map.Entry<?, ?> e : map.entrySet()) {
          if (!first) sb.append(',');
          first = false;

          escape(sb, String.valueOf(e.getKey()));
          sb.append(':');
          write(e.getValue(), sb, depth + 1, seen);
        }

        sb.append('}');
        seen.remove(v);
        return;
      }

      if (v instanceof Iterable) {
        if (seen.put(v, Boolean.TRUE) != null) {
          sb.append("\"[[circular]]\"");
          return;
        }

        sb.append('[');
        boolean first = true;
        for (Object e : (Iterable<?>) v) {
          if (!first) sb.append(',');
          first = false;
          write(e, sb, depth + 1, seen);
        }
        sb.append(']');
        seen.remove(v);
        return;
      }

      if (v.getClass().isArray()) {
        int len = Array.getLength(v);
        sb.append('[');
        for (int i = 0; i < len; i++) {
          if (i > 0) sb.append(',');
          write(Array.get(v, i), sb, depth + 1, seen);
        }
        sb.append(']');
        return;
      }

      escape(sb, String.valueOf(v));
    }

    private static void escape(StringBuilder sb, String s) {
      sb.append('"');
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        switch (c) {
          case '"':
            sb.append("\\\"");
            break;
          case '\\':
            sb.append("\\\\");
            break;
          case '\b':
            sb.append("\\b");
            break;
          case '\f':
            sb.append("\\f");
            break;
          case '\n':
            sb.append("\\n");
            break;
          case '\r':
            sb.append("\\r");
            break;
          case '\t':
            sb.append("\\t");
            break;
          default:
            if (c < 0x20) {
              sb.append(String.format("\\u%04x", (int) c));
            } else {
              sb.append(c);
            }
            break;
        }
      }
      sb.append('"');
    }
  }
}
