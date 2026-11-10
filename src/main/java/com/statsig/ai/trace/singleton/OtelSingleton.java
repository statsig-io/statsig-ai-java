package com.statsig.ai.trace.singleton;

import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

/**
 * Holds a global OtelSingleton instance that provides a TracerProvider. If not instantiated,
 * returns a no-op TracerProvider.
 */
public class OtelSingleton {
  private static volatile OtelSingleton instance;
  private final TracerProvider tracerProvider;

  protected OtelSingleton(TracerProvider tracerProvider) {
    this.tracerProvider = tracerProvider;
  }

  /** Create or return existing OtelSingleton instance. */
  public static OtelSingleton instantiate(TracerProvider tracerProvider) {
    if (instance != null) {
      System.out.println(
          "[Statsig OtelSingleton] OtelSingleton instance already exists. Returning the existing instance.");
      return instance;
    }
    synchronized (OtelSingleton.class) {
      if (instance == null) {
        instance = new OtelSingleton(tracerProvider);
      }
    }
    return instance;
  }

  /** Return current instance or fallback Noop version. */
  public static OtelSingleton getInstance() {
    if (instance != null) {
      return instance;
    }
    return NoopOtelSingleton.getInstance();
  }

  public TracerProvider getTracerProvider() {
    return tracerProvider;
  }

  public static TracerProvider getTracerProviderStatic() {
    return getInstance().getTracerProvider();
  }

  /**
   * Force-flushes pending spans when the stored tracer provider is an {@link SdkTracerProvider}.
   * Does not shut the provider down. Succeeds immediately when there is nothing to flush.
   */
  public static CompletableResultCode flushInstance() {
    // Read the field directly so an uninitialized singleton doesn't log the no-op warning.
    OtelSingleton current = instance;
    if (current != null && current.tracerProvider instanceof SdkTracerProvider) {
      return ((SdkTracerProvider) current.tracerProvider).forceFlush();
    }
    return CompletableResultCode.ofSuccess();
  }

  /** For testing or reset scenarios only. */
  static void reset() {
    instance = null;
  }
}
