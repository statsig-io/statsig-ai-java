package com.statsig.ai.trace.singleton;

import io.opentelemetry.api.GlobalOpenTelemetry;

/** No-op fallback when OtelSingleton has not been instantiated. */
final class NoopOtelSingleton extends OtelSingleton {
  private static final NoopOtelSingleton INSTANCE = new NoopOtelSingleton();

  private NoopOtelSingleton() {
    super(GlobalOpenTelemetry.getTracerProvider());
  }

  public static NoopOtelSingleton getInstance() {
    System.out.println(
        "[Statsig NoopOtelSingleton] NoopOtelSingleton instance is being used. "
            + "OtelSingleton has not been properly instantiated.");
    return INSTANCE;
  }
}
