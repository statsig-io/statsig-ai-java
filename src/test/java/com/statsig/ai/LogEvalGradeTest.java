package com.statsig.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.statsig.Statsig;
import com.statsig.StatsigOptions;
import com.statsig.StatsigUser;
import com.statsig.ai.testutil.MockScrapiServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class LogEvalGradeTest {
  private MockScrapiServer scrapi;
  private Statsig statsigCore;
  private StatsigAI statsigAI;

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
    statsigAI = null;
  }

  @Test
  public void logEvalGradeSendsEvalResultEvent() throws Exception {
    scrapi.enqueueLogEventResponse(200);

    StatsigUser user = new StatsigUser.Builder().setUserID("log_eval_user").build();

    Prompt prompt = statsigAI.getPrompt(user, "test_prompt");
    PromptConfig livePrompt = prompt.getLivePrompt();

    statsigAI.logEvalGrade(user, livePrompt, 0.8, "grader_123", new AIEvalGradeData("session-abc"));
    statsigCore.flushEvents().get();

    scrapi.takeRequest(2, TimeUnit.SECONDS);
    RecordedRequest logRequest = scrapi.takeRequest(2, TimeUnit.SECONDS);
    assertNotNull("Expected a log_event request", logRequest);
    assertEquals("/v1/log_event", logRequest.getPath());

    String body = readBody(logRequest);
    JSONObject payload = JSON.parseObject(body);
    JSONArray events = payload.getJSONArray("events");
    assertNotNull("Expected events array in log payload", events);

    // find the eval_result event
    JSONObject event = null;
    for (int i = 0; i < events.size(); i++) {
      JSONObject candidate = events.getJSONObject(i);
      if ("statsig::eval_result".equals(candidate.getString("eventName"))) {
        event = candidate;
        break;
      }
    }
    assertNotNull("Expected statsig::eval_result event in log payload", event);
    assertEquals("statsig::eval_result", event.getString("eventName"));
    assertEquals("test_prompt", event.getString("value"));

    JSONObject metadata = event.getJSONObject("metadata");
    assertEquals(livePrompt.getPromptName(), metadata.getString("ai_config_name"));
    assertEquals("grader_123", metadata.getString("grader_id"));
    assertEquals("0.8", metadata.getString("score"));
    assertEquals(livePrompt.getName(), metadata.getString("version_name"));
    assertEquals(livePrompt.getId(), metadata.getString("version_id"));
    assertEquals("session-abc", metadata.getString("session_id"));
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
