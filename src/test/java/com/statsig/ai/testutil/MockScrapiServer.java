package com.statsig.ai.testutil;

import com.statsig.ai.TestUtils;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

public final class MockScrapiServer implements AutoCloseable {

  private final MockWebServer server = new MockWebServer();
  private boolean started;

  private MockScrapiServer() {}

  public static MockScrapiServer create() {
    return new MockScrapiServer();
  }

  public MockScrapiServer enqueueDownloadConfigSpecsResource(String resourcePath)
      throws IOException {
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

  public MockScrapiServer start() throws IOException {
    if (started) {
      return this;
    }

    server.start();
    started = true;
    return this;
  }

  public RecordedRequest takeRequest(long timeout, TimeUnit unit) throws InterruptedException {
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
    server.shutdown();
    started = false;
  }
}
