package com.statsig.ai.testutil;

import com.statsig.OutputLogger;
import com.statsig.Statsig;
import com.statsig.StatsigOptions;
import com.statsig.ai.StatsigAI;
import com.statsig.ai.StatsigInitConfig;
import com.statsig.ai.TestUtils;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

public final class MockScrapiServer implements AutoCloseable {

  private static final String SDK_KEY = "secret-test";

  private final MockWebServer server = new MockWebServer();
  private Statsig statsigCore;
  private boolean started;

  private MockScrapiServer() {}

  public static MockScrapiServer create() {
    return new MockScrapiServer();
  }

  public MockScrapiServer enqueueDownloadConfigSpecsResource(String resourcePath) throws IOException {
    if (resourcePath == null) {
      throw new IllegalArgumentException("resourcePath");
    }
    return enqueueDownloadConfigSpecs(TestUtils.readResource(resourcePath));
  }

  public MockScrapiServer enqueueDownloadConfigSpecs(String body) {
    if (body == null) {
      throw new IllegalArgumentException("body");
    }
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body));
    return this;
  }

  public MockScrapiServer enqueueLogEventResponse(int statusCode) {
    server.enqueue(new MockResponse().setResponseCode(statusCode));
    return this;
  }

  public MockScrapiServer enqueue(MockResponse response) {
    if (response == null) {
      throw new IllegalArgumentException("response");
    }
    server.enqueue(response);
    return this;
  }

  public MockScrapiServer start() throws IOException, ExecutionException, InterruptedException {
    if (started) {
      return this;
    }

    server.start();

    StatsigOptions options =
        new StatsigOptions.Builder()
            .setSpecsUrl(server.url("/v2/download_config_specs").toString())
            .setLogEventUrl(server.url("/v1/log_event").toString())
            .setOutputLoggerLevel(OutputLogger.LogLevel.DEBUG)
            .build();

    statsigCore = new Statsig(SDK_KEY, options);
    statsigCore.initialize().get();
    started = true;
    return this;
  }

  public Statsig getStatsigCore() {
    ensureStarted();
    return statsigCore;
  }

  public StatsigAI newStatsigAI() {
    ensureStarted();
    return new StatsigAI(StatsigInitConfig.coreAttached(statsigCore));
  }

  public void flushEvents() throws ExecutionException, InterruptedException {
    ensureStarted();
    statsigCore.flushEvents().get();
  }

  public RecordedRequest takeRequest(long timeout, TimeUnit unit)
      throws InterruptedException {
    ensureStarted();
    return server.takeRequest(timeout, unit);
  }

  public String url(String path) {
    ensureStarted();
    return server.url(path).toString();
  }

  public MockWebServer getServer() {
    return server;
  }

  private void ensureStarted() {
    if (!started) {
      throw new IllegalStateException("MockScrapiServer has not been started.");
    }
  }

  @Override
  public void close() throws Exception {
    try {
      if (statsigCore != null) {
        try {
          statsigCore.shutdown().get(5, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
          statsigCore.shutdown().cancel(true);
        } catch (Exception ignore) {
          // swallow cleanup exceptions in tests
        } finally {
          statsigCore = null;
        }
      }
    } finally {
      server.shutdown();
      started = false;
    }
  }
}

