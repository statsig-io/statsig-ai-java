package com.statsig.ai.trace;

import com.statsig.StatsigUser;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Helpers for attaching Statsig-specific metadata to the current OpenTelemetry context.
 *
 * <p>The helpers that take a {@link Supplier} or {@link Runnable} run it with the new context
 * current and restore the previous context when it returns. The helpers that take a {@link Context}
 * return the new context without making it current.
 */
public final class StatsigContextUtils {
  private StatsigContextUtils() {}

  public static Context withPrompt(Context ctx, String promptName, String promptVersion) {
    if (promptName != null && !promptName.isEmpty()) {
      ctx = ctx.with(StatsigTraceConventions.CTX_ACTIVE_PROMPT, promptName);
    }
    if (promptVersion != null && !promptVersion.isEmpty()) {
      ctx = ctx.with(StatsigTraceConventions.CTX_ACTIVE_PROMPT_VERSION, promptVersion);
    }
    return ctx;
  }

  // ----------------------------------------------------------------------
  // User context
  // ----------------------------------------------------------------------

  /**
   * Returns {@code ctx} with the user's IDs. {@link StatsigSpanProcessor} stamps spans started in
   * it with {@code statsig.user_id} and {@code statsig.custom_ids}. Returns {@code ctx} unchanged
   * when {@code user} is null.
   */
  public static Context withUser(Context ctx, StatsigUser user) {
    if (user == null) {
      return ctx;
    }
    return ctx.with(StatsigTraceConventions.CTX_ACTIVE_USER, toUserContext(user));
  }

  /**
   * Runs {@code body} with the user's IDs in context, and returns its result. See {@link
   * #withUser(Context, StatsigUser)}.
   */
  public static <T> T withStatsigUserContext(StatsigUser user, Supplier<T> body) {
    Objects.requireNonNull(body, "body");
    return callIn(withUser(Context.current(), user), body);
  }

  /**
   * Runs {@code body} with the user's IDs in context. See {@link #withUser(Context, StatsigUser)}.
   */
  public static void withStatsigUserContext(StatsigUser user, Runnable body) {
    Objects.requireNonNull(body, "body");
    runIn(withUser(Context.current(), user), body);
  }

  public static UserContext getActiveUser(Context ctx) {
    return ctx.get(StatsigTraceConventions.CTX_ACTIVE_USER);
  }

  /**
   * Returns the span attributes for the user in {@code ctx}: {@code statsig.user_id}, and {@code
   * statsig.custom_ids} (compact JSON) when the user has custom IDs. Returns an empty map when
   * there's no user.
   */
  public static Map<String, String> getUserSpanAttrs(Context ctx) {
    UserContext user = getActiveUser(ctx);
    if (user == null) {
      return Collections.emptyMap();
    }
    Map<String, String> attrs = new LinkedHashMap<String, String>();
    attrs.put(StatsigTraceConventions.ATTR_USER_ID, user.getUserID());
    if (!user.getCustomIDs().isEmpty()) {
      attrs.put(StatsigTraceConventions.ATTR_CUSTOM_IDS, toJson(user.getCustomIDs()));
    }
    return Collections.unmodifiableMap(attrs);
  }

  // ----------------------------------------------------------------------
  // Statsig context
  // ----------------------------------------------------------------------

  /**
   * Returns {@code ctx} with a Statsig context holding the user's IDs and the activity ID. {@link
   * #getStatsigSpanAttrs} turns it into span attributes.
   *
   * <p>Replaces any Statsig context already in {@code ctx}.
   *
   * @param user the user, or null when there's only an activity ID. A missing user ID is recorded
   *     as {@code "unknown"}.
   * @param activityID the activity ID, or null or empty for none
   */
  public static Context withStatsigContext(Context ctx, StatsigUser user, String activityID) {
    UserContext userContext = user == null ? null : toUserContext(user);
    String activity = activityID == null || activityID.isEmpty() ? null : activityID;
    return ctx.with(
        StatsigTraceConventions.CTX_ACTIVE_CONTEXT, new StatsigContextItem(userContext, activity));
  }

  /**
   * Runs {@code body} in a Statsig context holding the user's IDs and the activity ID, and returns
   * its result. See {@link #withStatsigContext(Context, StatsigUser, String)}.
   */
  public static <T> T withStatsigContext(StatsigUser user, String activityID, Supplier<T> body) {
    Objects.requireNonNull(body, "body");
    return callIn(withStatsigContext(Context.current(), user, activityID), body);
  }

  /**
   * Runs {@code body} in a Statsig context holding the user's IDs and the activity ID. See {@link
   * #withStatsigContext(Context, StatsigUser, String)}.
   */
  public static void withStatsigContext(StatsigUser user, String activityID, Runnable body) {
    Objects.requireNonNull(body, "body");
    runIn(withStatsigContext(Context.current(), user, activityID), body);
  }

  /** Returns the Statsig context set by {@code withStatsigContext}, or null when there is none. */
  public static StatsigContextItem getActiveStatsigContext(Context ctx) {
    return ctx.get(StatsigTraceConventions.CTX_ACTIVE_CONTEXT);
  }

  /**
   * Returns the span attributes for the Statsig context in {@code ctx}: {@code statsig.user_id}
   * when it has a user, and {@code statsig.custom_ids} (compact JSON, including {@code
   * statsig.activity_id}) when there are any custom IDs. Returns an empty map when there are no
   * attributes.
   */
  public static Map<String, String> getStatsigSpanAttrs(Context ctx) {
    StatsigContextItem item = getActiveStatsigContext(ctx);
    if (item == null) {
      return Collections.emptyMap();
    }

    Map<String, String> attrs = new LinkedHashMap<String, String>();
    Map<String, String> customIDs = new LinkedHashMap<String, String>();
    UserContext user = item.getUser();
    if (user != null) {
      attrs.put(StatsigTraceConventions.ATTR_USER_ID, user.getUserID());
      customIDs.putAll(user.getCustomIDs());
    }
    if (item.getActivityID() != null) {
      customIDs.put(StatsigTraceConventions.ATTR_ACTIVITY_ID, item.getActivityID());
    }
    if (!customIDs.isEmpty()) {
      attrs.put(StatsigTraceConventions.ATTR_CUSTOM_IDS, toJson(customIDs));
    }
    return Collections.unmodifiableMap(attrs);
  }

  // ----------------------------------------------------------------------
  // Helpers
  // ----------------------------------------------------------------------

  private static UserContext toUserContext(StatsigUser user) {
    Map<String, String> customIDs = user.getCustomIDs();
    return new UserContext(
        user.getUserID(), customIDs == null ? Collections.<String, String>emptyMap() : customIDs);
  }

  private static <T> T callIn(Context ctx, Supplier<T> body) {
    try (Scope ignored = ctx.makeCurrent()) {
      return body.get();
    }
  }

  private static void runIn(Context ctx, Runnable body) {
    try (Scope ignored = ctx.makeCurrent()) {
      body.run();
    }
  }

  /** Compact JSON object, like JavaScript's {@code JSON.stringify}. */
  static String toJson(Map<String, String> map) {
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, String> entry : map.entrySet()) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      appendJsonString(sb, String.valueOf(entry.getKey()));
      sb.append(':');
      if (entry.getValue() == null) {
        sb.append("null");
      } else {
        appendJsonString(sb, entry.getValue());
      }
    }
    return sb.append('}').toString();
  }

  private static void appendJsonString(StringBuilder sb, String s) {
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
      }
    }
    sb.append('"');
  }
}
