package com.statsig.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.statsig.DynamicConfig;
import com.statsig.EvaluationDetails;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Ports the Node SDK's prompt rendering tests, which use mustache.js with escaping turned off. */
public class PromptVersionRenderingTest {

  @Test
  public void rendersTheTemplateVariables() throws Exception {
    JSONObject payload = JSON.parseObject(TestUtils.readResource("/prompt.json"));
    PromptVersion version =
        new PromptVersion(
            newDynamicConfig(
                payload.getString("key"),
                new HashMap<String, Object>(payload.getJSONObject("defaultValue"))),
            true);

    assertEquals(
        Collections.singletonList(new PromptMessage("system", "hoiiiiii test")),
        version.getPromptMessages(Collections.singletonMap("input", "test")));
  }

  @Test
  public void valuesAreNotHtmlEscaped() throws Exception {
    PromptVersion version = withPrompts(message("user", "<b>{{x}}</b> {{{x}}} {{&x}}"));

    assertEquals(
        "<b><i>\"Tom\" & 'Jerry'</i></b> <i>\"Tom\" & 'Jerry'</i> <i>\"Tom\" & 'Jerry'</i>",
        render(version, Collections.singletonMap("x", "<i>\"Tom\" & 'Jerry'</i>")));
  }

  @Test
  public void missingAndNullVariablesRenderAsEmptyStrings() throws Exception {
    PromptVersion version = withPrompts(message("user", "[{{missing}}][{{nothing}}][{{a.b}}]"));

    assertEquals("[][][]", render(version, Collections.singletonMap("nothing", null)));
  }

  @Test
  public void sectionsAndInvertedSectionsFollowJavaScriptTruthiness() throws Exception {
    PromptVersion version =
        withPrompts(message("user", "{{#vip}}VIP{{/vip}}{{^vip}}Guest{{/vip}}"));

    assertEquals("VIP", render(version, Collections.singletonMap("vip", true)));
    assertEquals("VIP", render(version, Collections.singletonMap("vip", "yes")));
    assertEquals("Guest", render(version, Collections.singletonMap("vip", false)));
    assertEquals("Guest", render(version, Collections.singletonMap("vip", "")));
    assertEquals("Guest", render(version, Collections.singletonMap("vip", 0)));
    assertEquals("Guest", render(version, Collections.<String, Object>emptyMap()));
  }

  @Test
  public void sectionsLoopOverListsAndReadNestedValues() throws Exception {
    PromptVersion version =
        withPrompts(
            message("user", "{{#items}}- {{name}} ({{qty}})\n{{/items}}{{^items}}none{{/items}}"),
            message("assistant", "Hi {{user.name}}"));
    List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
    items.add(item("apples", 2));
    items.add(item("pears", 1));
    Map<String, Object> params = new LinkedHashMap<String, Object>();
    params.put("items", items);
    params.put("user", Collections.singletonMap("name", "Ada"));

    assertEquals(
        Arrays.asList(
            new PromptMessage("user", "- apples (2)\n- pears (1)\n"),
            new PromptMessage("assistant", "Hi Ada")),
        version.getPromptMessages(params));

    params.put("items", Collections.emptyList());
    assertEquals("none", version.getPromptMessages(params).get(0).getContent());
  }

  @Test
  public void versionWithoutPromptsRendersOneEmptySystemMessage() throws Exception {
    PromptVersion version =
        new PromptVersion(newDynamicConfig("prompt:empty", new HashMap<String, Object>()), false);

    assertEquals(
        Collections.singletonList(new PromptMessage("system", "")),
        version.getPromptMessages(null));
  }

  @Test
  public void getValueReturnsTheValueOrTheFallback() throws Exception {
    Map<String, Object> value = new HashMap<String, Object>();
    value.put("model", "gpt-5");
    value.put("temperature", 1);
    value.put("tags", Arrays.asList("a", "b"));
    value.put("settings", Collections.singletonMap("mode", "fast"));
    value.put("nothing", null);
    PromptVersion version = new PromptVersion(newDynamicConfig("prompt:v", value), false);

    assertEquals("gpt-5", version.getValue("model", "fallback"));
    assertEquals("gpt-5", version.getValue("model", null));
    assertEquals("fallback", version.getValue("missing", "fallback"));
    assertNull(version.getValue("missing", null));
    assertEquals("fallback", version.getValue("nothing", "fallback"));
    // A value of another type gives the fallback, as in the Node core SDK.
    assertEquals(3, version.getValue("model", 3));
    assertEquals("fallback", version.getValue("temperature", "fallback"));
    // Numbers match numbers, lists match lists and arrays, maps match maps.
    assertEquals(1, version.getValue("temperature", 0.5));
    assertEquals(Arrays.asList("a", "b"), version.getValue("tags", new String[0]));
    assertEquals(
        Collections.singletonMap("mode", "fast"),
        version.getValue("settings", Collections.emptyMap()));
  }

  private static String render(PromptVersion version, Map<String, ?> params) {
    return version.getPromptMessages(params).get(0).getContent();
  }

  private static Map<String, Object> item(String name, int qty) {
    Map<String, Object> item = new LinkedHashMap<String, Object>();
    item.put("name", name);
    item.put("qty", qty);
    return item;
  }

  private static Map<String, Object> message(String role, String content) {
    Map<String, Object> message = new LinkedHashMap<String, Object>();
    message.put("role", role);
    message.put("content", content);
    return message;
  }

  @SafeVarargs
  private static PromptVersion withPrompts(Map<String, Object>... prompts) throws Exception {
    Map<String, Object> value = new HashMap<String, Object>();
    value.put("prompts", Arrays.asList(prompts));
    return new PromptVersion(newDynamicConfig("prompt:version-1", value), true);
  }

  private static DynamicConfig newDynamicConfig(String key, Map<String, Object> defaultValue)
      throws Exception {
    Constructor<DynamicConfig> ctor =
        DynamicConfig.class.getDeclaredConstructor(
            String.class, Map.class, String.class, EvaluationDetails.class, String.class);
    ctor.setAccessible(true);
    return ctor.newInstance(key, defaultValue, "", evaluationDetails(), "userID");
  }

  private static EvaluationDetails evaluationDetails() throws Exception {
    Constructor<EvaluationDetails> ctor =
        EvaluationDetails.class.getDeclaredConstructor(long.class, long.class, String.class);
    ctor.setAccessible(true);
    return ctor.newInstance(0L, 0L, "unit_test");
  }
}
