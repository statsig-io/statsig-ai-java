package com.statsig.ai.instrumentation.context;

import com.statsig.StatsigUser;
import com.statsig.ai.instrumentation.StatsigTraceConventions;
import io.opentelemetry.context.Context;
import java.util.Collections;
import java.util.Map;

/** Helpers for attaching Statsig-specific metadata to the current OpenTelemetry context. */
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

  public static Context withUser(Context ctx, StatsigUser user) {
    if (user == null) {
      return ctx;
    }
    String userID = user.getUserID();
    Map<String, String> customIDs = user.getCustomIDs();
    if (customIDs == null) {
      customIDs = Collections.emptyMap();
    }
    UserContext context = new UserContext(userID, customIDs);
    return ctx.with(StatsigTraceConventions.CTX_ACTIVE_USER, context);
  }

  public static UserContext getActiveUser(Context ctx) {
    return ctx.get(StatsigTraceConventions.CTX_ACTIVE_USER);
  }
}
