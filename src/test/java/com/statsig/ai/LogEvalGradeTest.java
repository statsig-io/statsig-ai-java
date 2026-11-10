package com.statsig.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.statsig.Statsig;
import com.statsig.StatsigOptions;
import com.statsig.StatsigUser;
import com.statsig.ai.testutil.MockScrapiServer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class LogEvalGradeTest {
  private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
  private static final String SPAN_ID = "b7ad6b7169203331";

  private MockScrapiServer scrapi;
  private Statsig statsigCore;
  private StatsigAI statsigAI;
  private SdkTracerProvider tracerProvider;
  private Tracer tracer;

  @Before
  public void setUp() throws IOException, InterruptedException, ExecutionException {
    scrapi = MockScrapiServer.create();
    scrapi.start();
    scrapi.enqueueDownloadConfigSpecsResource("/eval_proj_dcs.json");

    StatsigOptions options =
        new StatsigOptions.Builder()
            .setSpecsUrl(scrapi.url("/v2/download_config_specs"))
            .setLogEventUrl(scrapi.url("/v1/log_event"))
            .build();

    statsigCore = new Statsig("secret-test-key", options);
    statsigCore.initialize().get();
    statsigAI = new StatsigAI(StatsigInitConfig.coreAttached(statsigCore));

    tracerProvider = SdkTracerProvider.builder().build();
    tracer = tracerProvider.get("log-eval-grade-test");
  }

  @After
  public void tearDown() throws Exception {
    if (statsigCore != null) {
      statsigCore.shutdown().get();
      statsigCore = null;
    }
    if (scrapi != null) {
      scrapi.close();
      scrapi = null;
    }
    if (tracerProvider != null) {
      tracerProvider.shutdown().join(5, TimeUnit.SECONDS);
      tracerProvider = null;
    }
    statsigAI = null;
  }

  @Test
  public void logEvalGradeSendsEvalResultEvent() throws Exception {
    StatsigUser user = new StatsigUser.Builder().setUserID("log_eval_user").build();

    Prompt prompt = statsigAI.getPrompt(user, "test_prompt");
    PromptConfig livePrompt = prompt.getLivePrompt();

    statsigAI.logEvalGrade(user, livePrompt, 0.8, "grader_123", new AIEvalGradeData("session-abc"));

    JSONObject event = flushAndGetSingleEvalResult();
    assertEquals("test_prompt", event.getString("value"));

    JSONObject metadata = event.getJSONObject("metadata");
    assertEquals(livePrompt.getPromptName(), metadata.getString("ai_config_name"));
    assertEquals("grader_123", metadata.getString("grader_name"));
    assertFalse(metadata.containsKey("grader_id"));
    assertEquals("0.8", metadata.getString("score"));
    assertEquals(livePrompt.getName(), metadata.getString("version_name"));
    assertEquals(livePrompt.getId(), metadata.getString("version_id"));
    assertEquals("session-abc", metadata.getString("session_id"));
    assertEquals("true", metadata.getString("is_live"));
    assertEquals("sdk", metadata.getString("source"));
    // No active span and no IDs passed: the trace keys are omitted, not empty.
    assertFalse(metadata.containsKey("trace_id"));
    assertFalse(metadata.containsKey("span_id"));
    assertFalse(metadata.containsKey("reasoning"));
  }

  @Test
  public void logEvalGradeMarksCandidateAsNotLive() throws Exception {
    StatsigUser user = new StatsigUser.Builder().setUserID("log_eval_user").build();

    Prompt prompt = statsigAI.getPrompt(user, "test_prompt");
    assertTrue(prompt.getLivePrompt().isLiveForUser());
    PromptConfig candidate = prompt.getCandidatePrompts()[0];
    assertFalse(candidate.isLiveForUser());

    statsigAI.logEvalGrade(user, candidate, 0.5, "grader_123", new AIEvalGradeData("session-abc"));

    JSONObject metadata = flushAndGetSingleEvalResult().getJSONObject("metadata");
    assertEquals("false", metadata.getString("is_live"));
    assertEquals(candidate.getId(), metadata.getString("version_id"));
  }

  @Test
  public void logEvalGradeUsesActiveSpanIds() throws Exception {
    StatsigUser user = new StatsigUser.Builder().setUserID("log_eval_user").build();
    PromptConfig livePrompt = statsigAI.getPrompt(user, "test_prompt").getLivePrompt();

    Span span = tracer.spanBuilder("llm-call").startSpan();
    try (Scope ignored = span.makeCurrent()) {
      statsigAI.logEvalGrade(user, livePrompt, 1.0, "grader_123", new AIEvalGradeData("s"));
    } finally {
      span.end();
    }

    JSONObject metadata = flushAndGetSingleEvalResult().getJSONObject("metadata");
    assertEquals(span.getSpanContext().getTraceId(), metadata.getString("trace_id"));
    assertEquals(span.getSpanContext().getSpanId(), metadata.getString("span_id"));
  }

  @Test
  public void logEvalGradePrefersPassedTraceIdsOverActiveSpan() throws Exception {
    StatsigUser user = new StatsigUser.Builder().setUserID("log_eval_user").build();
    PromptConfig livePrompt = statsigAI.getPrompt(user, "test_prompt").getLivePrompt();

    AIEvalGradeData data = new AIEvalGradeData("s");
    data.setTraceID(TRACE_ID);
    data.setSpanID(SPAN_ID);

    Span span = tracer.spanBuilder("unrelated").startSpan();
    try (Scope ignored = span.makeCurrent()) {
      statsigAI.logEvalGrade(user, livePrompt, 1.0, "grader_123", data);
    } finally {
      span.end();
    }

    JSONObject metadata = flushAndGetSingleEvalResult().getJSONObject("metadata");
    assertEquals(TRACE_ID, metadata.getString("trace_id"));
    assertEquals(SPAN_ID, metadata.getString("span_id"));
  }

  @Test
  public void logEvalGradeIncludesReasoningAndMergesMetadata() throws Exception {
    StatsigUser user = new StatsigUser.Builder().setUserID("log_eval_user").build();
    PromptConfig livePrompt = statsigAI.getPrompt(user, "test_prompt").getLivePrompt();

    Map<String, String> extra = new HashMap<>();
    extra.put("judge_model", "gpt-4o-mini");
    extra.put("source", "custom_source");

    AIEvalGradeData data = new AIEvalGradeData();
    data.setReasoning("Answer cites the right doc.");
    data.setMetadata(extra);

    statsigAI.logEvalGrade(user, livePrompt, 0.9, "grader_123", data);

    JSONObject metadata = flushAndGetSingleEvalResult().getJSONObject("metadata");
    assertEquals("Answer cites the right doc.", metadata.getString("reasoning"));
    assertEquals("gpt-4o-mini", metadata.getString("judge_model"));
    // Caller metadata is merged last, so it wins on conflicts (matches Node).
    assertEquals("custom_source", metadata.getString("source"));
    // No session ID set: sent as an empty string.
    assertEquals("", metadata.getString("session_id"));
  }

  @Test
  public void logEvalGradeAddsActivityIdToCustomIds() throws Exception {
    Map<String, String> customIDs = new HashMap<>();
    customIDs.put("companyID", "company-1");
    StatsigUser user =
        new StatsigUser.Builder().setUserID("log_eval_user").setCustomIDs(customIDs).build();
    PromptConfig livePrompt = statsigAI.getPrompt(user, "test_prompt").getLivePrompt();

    AIEvalGradeData data = new AIEvalGradeData("s");
    data.setActivityID("activity-42");

    statsigAI.logEvalGrade(user, livePrompt, 0.7, "grader_123", data);

    JSONObject eventUser = flushAndGetSingleEvalResult().getJSONObject("user");
    assertNotNull("Expected user on the event", eventUser);
    assertEquals("log_eval_user", eventUser.getString("userID"));
    JSONObject eventCustomIDs = eventUser.getJSONObject("customIDs");
    assertEquals("activity-42", eventCustomIDs.getString("statsig.activity_id"));
    assertEquals("company-1", eventCustomIDs.getString("companyID"));

    // The caller's user is left untouched.
    assertEquals(Collections.singletonMap("companyID", "company-1"), user.getCustomIDs());
    assertNull(user.getCustomIDs().get("statsig.activity_id"));
  }

  private JSONObject flushAndGetSingleEvalResult() throws Exception {
    List<JSONObject> results = flushAndGetEvalResults();
    assertEquals("Expected exactly one statsig::eval_result event", 1, results.size());
    return results.get(0);
  }

  private List<JSONObject> flushAndGetEvalResults() throws Exception {
    scrapi.enqueueLogEventResponse(200);
    statsigCore.flushEvents().get();

    scrapi.takeRequest(2, TimeUnit.SECONDS);
    RecordedRequest logRequest = scrapi.takeRequest(2, TimeUnit.SECONDS);
    assertNotNull("Expected a log_event request", logRequest);
    assertEquals("/v1/log_event", logRequest.getPath());

    JSONObject payload = JSON.parseObject(readBody(logRequest));
    JSONArray events = payload.getJSONArray("events");
    assertNotNull("Expected events array in log payload", events);

    List<JSONObject> results = new ArrayList<>();
    for (int i = 0; i < events.size(); i++) {
      JSONObject candidate = events.getJSONObject(i);
      if ("statsig::eval_result".equals(candidate.getString("eventName"))) {
        results.add(candidate);
      }
    }
    return results;
  }

  private static String readBody(RecordedRequest request) throws IOException {
    byte[] bodyBytes = request.getBody().readByteArray();
    String encoding = request.getHeader("Content-Encoding");
    if (encoding != null && encoding.equalsIgnoreCase("gzip")) {
      try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bodyBytes));
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[1024];
        int len;
        while ((len = gzip.read(buffer)) != -1) {
          out.write(buffer, 0, len);
        }
        return out.toString(StandardCharsets.UTF_8.name());
      }
    }
    return new String(bodyBytes, StandardCharsets.UTF_8);
  }
}
