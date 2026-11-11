package com.statsig.ai.trace.singleton;

import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

/**
 * Holds a global OtelSingleton instance that provides a TracerProvider and the tracer's
 * environment. If not instantiated, returns a no-op TracerProvider.
 */
public class OtelSingleton {
  private static volatile OtelSingleton instance;
  private final TracerProvider tracerProvider;
  private final String environment;

  protected OtelSingleton(TracerProvider tracerProvider) {
    this(tracerProvider, null);
  }

  protected OtelSingleton(TracerProvider tracerProvider, String environment) {
    this.tracerProvider = tracerProvider;
    this.environment = environment;
  }

  /** Create or return existing OtelSingleton instance, with no environment. */
  public static OtelSingleton instantiate(TracerProvider tracerProvider) {
    return instantiate(tracerProvider, null);
  }

  /**
   * Create or return existing OtelSingleton instance.
   *
   * @param environment the environment from {@code InitializeTracingOptions}, or null for none
   */
  public static OtelSingleton instantiate(TracerProvider tracerProvider, String environment) {
    if (instance != null) {
      System.out.println(
          "[Statsig OtelSingleton] OtelSingleton instance already exists. Returning the existing instance.");
      return instance;
    }
    synchronized (OtelSingleton.class) {
      if (instance == null) {
        instance = new OtelSingleton(tracerProvider, environment);
      }
    }
    return instance;
  }

  /**
   * Returns true once an instance has been stored, for example by {@code Otel.initialize}. Until
   * then spans go to the no-op fallback and aren't exported.
   */
  public static boolean isInitialized() {
    return instance != null;
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

  /** Returns the environment the tracer was initialized with, or null when there's none. */
  public String getEnvironment() {
    return environment;
  }

  /**
   * Returns the environment the tracer was initialized with, or null when there's none or tracing
   * isn't initialized.
   */
  public static String getEnvironmentStatic() {
    // Read the field directly so an uninitialized singleton doesn't log the no-op warning.
    OtelSingleton current = instance;
    return current == null ? null : current.environment;
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
