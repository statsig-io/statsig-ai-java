package com.statsig.ai.trace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.statsig.StatsigUser;
import io.opentelemetry.context.Context;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class StatsigContextUtilsTest {

  private static StatsigUser user(String userID, Map<String, String> customIDs) {
    StatsigUser.Builder builder = new StatsigUser.Builder().setUserID(userID);
    if (customIDs != null) {
      builder.setCustomIDs(customIDs);
    }
    return builder.build();
  }

  private static Map<String, String> attrs(String... keyValues) {
    Map<String, String> map = new HashMap<String, String>();
    for (int i = 0; i < keyValues.length; i += 2) {
      map.put(keyValues[i], keyValues[i + 1]);
    }
    return map;
  }

  @Test
  public void withStatsigContextAddsUserAndActivityID() {
    StatsigUser user = user("user-1", Collections.singletonMap("team", "red"));

    String result =
        StatsigContextUtils.withStatsigContext(
            user,
            "activity-1",
            () -> {
              assertEquals(
                  attrs(
                      "statsig.user_id",
                      "user-1",
                      "statsig.custom_ids",
                      "{\"team\":\"red\",\"statsig.activity_id\":\"activity-1\"}"),
                  StatsigContextUtils.getStatsigSpanAttrs(Context.current()));
              return "done";
            });

    assertEquals("done", result);
    assertTrue(StatsigContextUtils.getStatsigSpanAttrs(Context.current()).isEmpty());
    assertNull(StatsigContextUtils.getActiveStatsigContext(Context.current()));
  }

  @Test
  public void userWithoutCustomIDsOrActivityIDOnlyHasUserID() {
    StatsigContextUtils.withStatsigContext(
        user("user-2", null),
        null,
        () ->
            assertEquals(
                attrs("statsig.user_id", "user-2"),
                StatsigContextUtils.getStatsigSpanAttrs(Context.current())));
  }

  @Test
  public void activityIDWithoutUserOnlyHasCustomIDs() {
    StatsigContextUtils.withStatsigContext(
        null,
        "activity-2",
        () ->
            assertEquals(
                attrs("statsig.custom_ids", "{\"statsig.activity_id\":\"activity-2\"}"),
                StatsigContextUtils.getStatsigSpanAttrs(Context.current())));
  }

  @Test
  public void noUserAndEmptyActivityIDHasNoAttrs() {
    StatsigContextUtils.withStatsigContext(
        null,
        "",
        () -> {
          StatsigContextItem item = StatsigContextUtils.getActiveStatsigContext(Context.current());
          assertNull(item.getUser());
          assertNull(item.getActivityID());
          assertTrue(StatsigContextUtils.getStatsigSpanAttrs(Context.current()).isEmpty());
        });
  }

  @Test
  public void missingUserIDIsRecordedAsUnknown() {
    StatsigContextUtils.withStatsigContext(
        user("", null),
        null,
        () ->
            assertEquals(
                attrs("statsig.user_id", "unknown"),
                StatsigContextUtils.getStatsigSpanAttrs(Context.current())));
  }

  @Test
  public void contextOverloadReturnsContextWithoutMakingItCurrent() {
    Context ctx =
        StatsigContextUtils.withStatsigContext(
            Context.root(), user("user-3", Collections.singletonMap("org", "acme")), "a-3");

    assertNull(StatsigContextUtils.getActiveStatsigContext(Context.current()));
    StatsigContextItem item = StatsigContextUtils.getActiveStatsigContext(ctx);
    assertEquals("user-3", item.getUser().getUserID());
    assertEquals(Collections.singletonMap("org", "acme"), item.getUser().getCustomIDs());
    assertEquals("a-3", item.getActivityID());
  }

  @Test
  public void innerStatsigContextReplacesOuter() {
    AtomicReference<Map<String, String>> inner = new AtomicReference<Map<String, String>>();
    StatsigContextUtils.withStatsigContext(
        user("outer-user", null),
        "outer-activity",
        () ->
            StatsigContextUtils.withStatsigContext(
                null,
                "inner-activity",
                () -> inner.set(StatsigContextUtils.getStatsigSpanAttrs(Context.current()))));

    assertEquals(
        attrs("statsig.custom_ids", "{\"statsig.activity_id\":\"inner-activity\"}"), inner.get());
  }

  @Test
  public void customIDsAreEscapedAsJson() {
    StatsigContextUtils.withStatsigContext(
        user("user-4", Collections.singletonMap("k\"ey", "a\\b\n\u0001")),
        null,
        () ->
            assertEquals(
                "{\"k\\\"ey\":\"a\\\\b\\n\\u0001\"}",
                StatsigContextUtils.getStatsigSpanAttrs(Context.current())
                    .get("statsig.custom_ids")));
  }

  @Test
  public void noPromptHasNoPromptAttrs() {
    assertTrue(StatsigContextUtils.getPromptSpanAttrs(Context.root()).isEmpty());
  }

  @Test
  public void withPromptVersionAddsNameVersionAndID() {
    Context ctx = StatsigContextUtils.withPromptVersion(Context.root(), "bot", "V1", "v1-id");

    assertEquals(
        attrs(
            "statsig.llm.prompt_name",
            "bot",
            "statsig.llm.prompt_version",
            "V1",
            "statsig.llm.prompt_version_id",
            "v1-id"),
        StatsigContextUtils.getPromptSpanAttrs(ctx));
  }

  @Test
  public void withPromptVersionReplacesTheOuterVersionAndSkipsEmptyValues() {
    Context outer = StatsigContextUtils.withPromptVersion(Context.root(), "bot", "V1", "v1-id");
    Context inner = StatsigContextUtils.withPromptVersion(outer, "bot", "V2", null);

    assertEquals(
        attrs("statsig.llm.prompt_name", "bot", "statsig.llm.prompt_version", "V2"),
        StatsigContextUtils.getPromptSpanAttrs(inner));
    assertTrue(
        StatsigContextUtils.getPromptSpanAttrs(
                StatsigContextUtils.withPromptVersion(Context.root(), "", null, ""))
            .isEmpty());
  }

  @Test
  public void withPromptOnlySetsTheValuesItIsGiven() {
    Context outer = StatsigContextUtils.withPromptVersion(Context.root(), "bot", "V1", "v1-id");

    assertEquals(
        attrs(
            "statsig.llm.prompt_name",
            "other-bot",
            "statsig.llm.prompt_version",
            "V1",
            "statsig.llm.prompt_version_id",
            "v1-id"),
        StatsigContextUtils.getPromptSpanAttrs(
            StatsigContextUtils.withPrompt(outer, "other-bot", null)));
  }
}
