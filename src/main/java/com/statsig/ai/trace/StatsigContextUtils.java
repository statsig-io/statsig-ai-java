package com.statsig.ai.trace;

import com.statsig.StatsigUser;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
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

  // ----------------------------------------------------------------------
  // Prompt
  // ----------------------------------------------------------------------

  // The template variables aren't known when a version's scope starts; they arrive later, when
  // PromptVersion.getPromptMessages renders the prompt inside it. Contexts are immutable, so the
  // scope holds this object and rendering fills it in, as in the Node SDK.
  private static final class ActivePromptTemplate {
    private final String versionID;
    private volatile Map<String, Object> variables;

    ActivePromptTemplate(String versionID) {
      this.versionID = versionID;
    }
  }

  private static final ContextKey<ActivePromptTemplate> CTX_ACTIVE_PROMPT_TEMPLATE =
      ContextKey.named("STATSIG_CTX_ACTIVE_PROMPT_TEMPLATE");

  /**
   * Returns {@code ctx} with the prompt name, version name and version ID. Model call spans started
   * in it, and their {@code statsig::gen_ai} events, get the attributes from {@link
   * #getPromptSpanAttrs}.
   *
   * <p>Replaces all three values already in {@code ctx}, so an inner version doesn't inherit an
   * outer version's name or ID. A null or empty value adds no attribute. Template variables that
   * this version renders inside the new context are recorded with {@link
   * #recordPromptTemplateVariables}.
   */
  public static Context withPromptVersion(
      Context ctx, String promptName, String versionName, String versionID) {
    return ctx.with(StatsigTraceConventions.CTX_ACTIVE_PROMPT, nullToEmpty(promptName))
        .with(StatsigTraceConventions.CTX_ACTIVE_PROMPT_VERSION, nullToEmpty(versionName))
        .with(StatsigTraceConventions.CTX_ACTIVE_PROMPT_VERSION_ID, nullToEmpty(versionID))
        .with(CTX_ACTIVE_PROMPT_TEMPLATE, new ActivePromptTemplate(nullToEmpty(versionID)));
  }

  /**
   * Records the variables a prompt version rendered its template with, in the current version scope
   * from {@link #withPromptVersion}. Does nothing outside a scope, or when the scope is for a
   * different version, so rendering another version doesn't mislabel the scope's spans. Called by
   * {@code PromptVersion.getPromptMessages}.
   */
  public static void recordPromptTemplateVariables(String versionID, Map<String, ?> variables) {
    ActivePromptTemplate template = Context.current().get(CTX_ACTIVE_PROMPT_TEMPLATE);
    if (template != null && template.versionID.equals(versionID) && variables != null) {
      template.variables =
          Collections.unmodifiableMap(new LinkedHashMap<String, Object>(variables));
    }
  }

  /**
   * Returns the span attributes for the prompt in {@code ctx}: {@code statsig.llm.prompt_name},
   * {@code statsig.llm.prompt_version} and {@code statsig.llm.prompt_version_id}, each only when
   * it's set and not empty. Returns an empty map when none are.
   */
  public static Map<String, String> getPromptSpanAttrs(Context ctx) {
    Map<String, String> attrs = new LinkedHashMap<String, String>();
    putIfNotEmpty(
        attrs,
        StatsigTraceConventions.ATTR_LLM_PROMPT_NAME,
        ctx.get(StatsigTraceConventions.CTX_ACTIVE_PROMPT));
    putIfNotEmpty(
        attrs,
        StatsigTraceConventions.ATTR_LLM_PROMPT_VERSION,
        ctx.get(StatsigTraceConventions.CTX_ACTIVE_PROMPT_VERSION));
    putIfNotEmpty(
        attrs,
        StatsigTraceConventions.ATTR_LLM_PROMPT_VERSION_ID,
        ctx.get(StatsigTraceConventions.CTX_ACTIVE_PROMPT_VERSION_ID));
    if (attrs.isEmpty()) {
      return Collections.emptyMap();
    }
    return Collections.unmodifiableMap(attrs);
  }

  /**
   * Like {@link #getPromptSpanAttrs(Context)}, plus {@code statsig.llm.prompt_template_variables}
   * (a map, recorded as JSON) when {@code includeTemplateVariables} is set and the version in
   * {@code ctx} has rendered its template. Template variables usually hold user input, so model
   * call spans include them only when input message capture is on, as in Node.
   */
  public static Map<String, Object> getPromptSpanAttrs(
      Context ctx, boolean includeTemplateVariables) {
    Map<String, Object> attrs = new LinkedHashMap<String, Object>(getPromptSpanAttrs(ctx));
    ActivePromptTemplate template = ctx.get(CTX_ACTIVE_PROMPT_TEMPLATE);
    Map<String, Object> variables = template == null ? null : template.variables;
    if (includeTemplateVariables && variables != null) {
      attrs.put(StatsigTraceConventions.ATTR_LLM_PROMPT_TEMPLATE_VARIABLES, variables);
    }
    return attrs;
  }

  private static void putIfNotEmpty(Map<String, String> attrs, String key, String value) {
    if (value != null && !value.isEmpty()) {
      attrs.put(key, value);
    }
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
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
   * Returns {@code ctx} with a Statsig context holding the user's IDs and the activity ID. OpenAI
   * model call spans started in it, and their {@code statsig::gen_ai} events, get the attributes
   * from {@link #getStatsigSpanAttrs}.
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
  // Environment
  // ----------------------------------------------------------------------

  /**
   * Returns {@code ctx} with a Statsig environment. {@link StatsigSpanProcessor} tags spans started
   * in it with {@code statsig.environment}, which overrides the tracer's {@code
   * deployment.environment.name} resource for those spans only. OpenAI model call spans also record
   * it in their {@code statsig::gen_ai} events. An inner environment replaces an outer one.
   *
   * <p>Use {@code production} for live traffic. {@code eval} and {@code online_eval_judge} are
   * reserved for spans Statsig generates (eval runs and online eval judges).
   *
   * @param environment the environment; an empty string adds no attribute
   */
  public static Context withStatsigEnvironment(Context ctx, String environment) {
    return ctx.with(
        StatsigTraceConventions.CTX_ACTIVE_ENVIRONMENT, environment == null ? "" : environment);
  }

  /**
   * Runs {@code body} with a Statsig environment, and returns its result. See {@link
   * #withStatsigEnvironment(Context, String)}.
   */
  public static <T> T withStatsigEnvironment(String environment, Supplier<T> body) {
    Objects.requireNonNull(body, "body");
    return callIn(withStatsigEnvironment(Context.current(), environment), body);
  }

  /**
   * Runs {@code body} with a Statsig environment. See {@link #withStatsigEnvironment(Context,
   * String)}.
   */
  public static void withStatsigEnvironment(String environment, Runnable body) {
    Objects.requireNonNull(body, "body");
    runIn(withStatsigEnvironment(Context.current(), environment), body);
  }

  /**
   * Returns {@code statsig.environment} for the environment in {@code ctx}, or an empty map when
   * there's no environment or it's empty.
   */
  public static Map<String, String> getEnvironmentSpanAttrs(Context ctx) {
    String environment = ctx.get(StatsigTraceConventions.CTX_ACTIVE_ENVIRONMENT);
    if (environment == null || environment.isEmpty()) {
      return Collections.emptyMap();
    }
    return Collections.singletonMap(StatsigTraceConventions.ATTR_ENVIRONMENT, environment);
  }

  // ----------------------------------------------------------------------
  // Incoming trace context
  // ----------------------------------------------------------------------

  private static final String[] TRACE_HEADERS = {"traceparent", "tracestate"};

  /**
   * Returns {@code ctx} in the trace named by the request's W3C {@code traceparent} and {@code
   * tracestate} headers, so spans started in it become children of the caller's span. Header names
   * are matched case-insensitively; other headers are ignored.
   *
   * <p>Returns {@code ctx} unchanged when there's no valid {@code traceparent}, or when the span in
   * {@code ctx} is already in that trace (for example, a span from OpenTelemetry HTTP server
   * instrumentation).
   *
   * @param headers the request headers; null is treated as no headers
   */
  public static Context withIncomingTraceContext(Context ctx, Map<String, String> headers) {
    if (headers == null) {
      return ctx;
    }
    return withIncomingTraceContext(ctx, name -> getHeaderIgnoreCase(headers, name));
  }

  /**
   * Like {@link #withIncomingTraceContext(Context, Map)}, reading each header with {@code
   * getHeader}, for example a servlet request's {@code getHeader}. It's called with the names
   * {@code traceparent} and {@code tracestate}, and returns null for a missing header.
   *
   * @param getHeader returns a request header's value; null is treated as no headers
   */
  public static Context withIncomingTraceContext(Context ctx, Function<String, String> getHeader) {
    if (getHeader == null) {
      return ctx;
    }
    Map<String, String> carrier = new HashMap<String, String>();
    for (String name : TRACE_HEADERS) {
      String value = getHeader.apply(name);
      if (value != null) {
        carrier.put(name, value);
      }
    }

    SpanContext incoming =
        Span.fromContext(
                W3CTraceContextPropagator.getInstance()
                    .extract(Context.root(), carrier, MapGetter.INSTANCE))
            .getSpanContext();
    if (!incoming.isValid()) {
      return ctx;
    }
    // OpenTelemetry HTTP instrumentation may already have started a span in this trace.
    if (Span.fromContext(ctx).getSpanContext().getTraceId().equals(incoming.getTraceId())) {
      return ctx;
    }
    return ctx.with(Span.wrap(incoming));
  }

  /**
   * Runs {@code body} in the trace named by the request's {@code traceparent} and {@code
   * tracestate} headers, and returns its result. Without a valid {@code traceparent}, {@code body}
   * runs unchanged. See {@link #withIncomingTraceContext(Context, Map)}.
   */
  public static <T> T withIncomingTraceContext(Map<String, String> headers, Supplier<T> body) {
    Objects.requireNonNull(body, "body");
    return callIn(withIncomingTraceContext(Context.current(), headers), body);
  }

  /**
   * Runs {@code body} in the trace named by the request's {@code traceparent} and {@code
   * tracestate} headers. Without a valid {@code traceparent}, {@code body} runs unchanged. See
   * {@link #withIncomingTraceContext(Context, Map)}.
   */
  public static void withIncomingTraceContext(Map<String, String> headers, Runnable body) {
    Objects.requireNonNull(body, "body");
    runIn(withIncomingTraceContext(Context.current(), headers), body);
  }

  /**
   * Runs {@code body} in the trace named by the {@code traceparent} and {@code tracestate} headers
   * that {@code getHeader} returns, and returns its result. See {@link
   * #withIncomingTraceContext(Context, Function)}.
   */
  public static <T> T withIncomingTraceContext(
      Function<String, String> getHeader, Supplier<T> body) {
    Objects.requireNonNull(body, "body");
    return callIn(withIncomingTraceContext(Context.current(), getHeader), body);
  }

  /**
   * Runs {@code body} in the trace named by the {@code traceparent} and {@code tracestate} headers
   * that {@code getHeader} returns. See {@link #withIncomingTraceContext(Context, Function)}.
   */
  public static void withIncomingTraceContext(Function<String, String> getHeader, Runnable body) {
    Objects.requireNonNull(body, "body");
    runIn(withIncomingTraceContext(Context.current(), getHeader), body);
  }

  private static String getHeaderIgnoreCase(Map<String, String> headers, String name) {
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      if (name.equalsIgnoreCase(entry.getKey())) {
        return entry.getValue();
      }
    }
    return null;
  }

  private enum MapGetter implements TextMapGetter<Map<String, String>> {
    INSTANCE;

    @Override
    public Iterable<String> keys(Map<String, String> carrier) {
      return carrier.keySet();
    }

    @Override
    public String get(Map<String, String> carrier, String key) {
      return carrier == null ? null : carrier.get(key);
    }
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
