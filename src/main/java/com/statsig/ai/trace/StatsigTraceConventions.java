package com.statsig.ai.trace;

import io.opentelemetry.context.ContextKey;

/** Shared attribute names and context keys used across Statsig tracing helpers. */
public final class StatsigTraceConventions {
  private StatsigTraceConventions() {}

  public static final String ATTR_SPAN_TYPE = "statsig.span.type";
  public static final String SPAN_TYPE_GEN_AI = "gen_ai";

  public static final String ATTR_GEN_AI_SPAN_TYPE = "statsig.span.gen_ai.type";
  public static final String GEN_AI_SPAN_TYPE_WORKFLOW = "workflow";
  public static final String GEN_AI_SPAN_TYPE_TOOL = "tool";
  public static final String GEN_AI_SPAN_TYPE_CHAT = "chat";
  public static final String GEN_AI_SPAN_TYPE_GENERATE_CONTENT = "generate_content";

  public static final String ATTR_SPAN_LLM_ROOT = "statsig.span.llm_root";
  public static final String SPAN_LLM_ROOT_VALUE = "1";

  public static final String ATTR_LLM_PROMPT_NAME = "statsig.llm.prompt_name";
  public static final String ATTR_LLM_PROMPT_VERSION = "statsig.llm.prompt_version";

  public static final String ATTR_USER_ID = "statsig.user_id";
  public static final String ATTR_CUSTOM_IDS = "statsig.custom_ids";
  public static final String ATTR_ACTIVITY_ID = "statsig.activity_id";

  public static final ContextKey<String> CTX_ACTIVE_PROMPT =
      ContextKey.named("STATSIG_CTX_ACTIVE_PROMPT");
  public static final ContextKey<String> CTX_ACTIVE_PROMPT_VERSION =
      ContextKey.named("STATSIG_CTX_ACTIVE_PROMPT_VERSION");
  public static final ContextKey<UserContext> CTX_ACTIVE_USER =
      ContextKey.named("STATSIG_CTX_ACTIVE_USER");
  public static final ContextKey<String> CTX_GEN_AI_ROOT =
      ContextKey.named("STATSIG_CTX_GEN_AI_ROOT");
}
