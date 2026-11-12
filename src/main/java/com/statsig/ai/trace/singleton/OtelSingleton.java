package com.statsig.ai.trace.singleton;

import com.statsig.ai.trace.StatsigResourceAttributes;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

/**
 * Holds a global OtelSingleton instance that provides a TracerProvider and the tracer's service
 * name, version and environment. If not instantiated, returns a no-op TracerProvider.
 */
public class OtelSingleton {
  private static volatile OtelSingleton instance;
  private final TracerProvider tracerProvider;
  private final StatsigResourceAttributes resources;

  protected OtelSingleton(TracerProvider tracerProvider) {
    this(tracerProvider, StatsigResourceAttributes.create(null, null, null));
  }

  protected OtelSingleton(TracerProvider tracerProvider, StatsigResourceAttributes resources) {
    this.tracerProvider = tracerProvider;
    this.resources = resources;
  }

  /**
   * Create or return existing OtelSingleton instance, with no environment. The service name comes
   * from the {@code OTEL_SERVICE_NAME} or {@code SERVICE_NAME} environment variable.
   */
  public static OtelSingleton instantiate(TracerProvider tracerProvider) {
    return instantiate(tracerProvider, (String) null);
  }

  /**
   * Create or return existing OtelSingleton instance. The service name comes from the {@code
   * OTEL_SERVICE_NAME} or {@code SERVICE_NAME} environment variable.
   *
   * @param environment the environment from {@code InitializeTracingOptions}, or null for none
   */
  public static OtelSingleton instantiate(TracerProvider tracerProvider, String environment) {
    return instantiate(tracerProvider, StatsigResourceAttributes.create(null, null, environment));
  }

  /**
   * Create or return existing OtelSingleton instance.
   *
   * @param resources the service name, version and environment from {@code
   *     InitializeTracingOptions}
   */
  public static OtelSingleton instantiate(
      TracerProvider tracerProvider, StatsigResourceAttributes resources) {
    if (instance != null) {
      System.out.println(
          "[Statsig OtelSingleton] OtelSingleton instance already exists. Returning the existing instance.");
      return instance;
    }
    synchronized (OtelSingleton.class) {
      if (instance == null) {
        instance = new OtelSingleton(tracerProvider, resources);
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
    return resources.getEnvironment();
  }

  /** Returns the service name, version and environment the tracer was initialized with. */
  public StatsigResourceAttributes getResourceAttributes() {
    return resources;
  }

  /**
   * Returns the environment the tracer was initialized with, or null when there's none or tracing
   * isn't initialized.
   */
  public static String getEnvironmentStatic() {
    // Read the field directly so an uninitialized singleton doesn't log the no-op warning.
    OtelSingleton current = instance;
    return current == null ? null : current.getEnvironment();
  }

  /**
   * Returns the service name, version and environment the tracer was initialized with. When tracing
   * isn't initialized, like Node, the service name still comes from the {@code OTEL_SERVICE_NAME}
   * or {@code SERVICE_NAME} environment variable.
   */
  public static StatsigResourceAttributes getResourceAttributesStatic() {
    // Read the field directly so an uninitialized singleton doesn't log the no-op warning.
    OtelSingleton current = instance;
    return current == null
        ? StatsigResourceAttributes.create(null, null, null)
        : current.getResourceAttributes();
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
