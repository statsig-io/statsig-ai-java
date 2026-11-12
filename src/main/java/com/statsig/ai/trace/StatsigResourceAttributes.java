package com.statsig.ai.trace;

import java.util.function.Function;

/**
 * The service name, service version and environment tracing was set up with, as in the Node SDK's
 * {@code otel/resources.ts}. {@code Otel.initialize} records them on the tracer provider's
 * resource, and {@code statsig::gen_ai} events record them as the {@code service}, {@code version}
 * and {@code environment} custom IDs of the event user.
 */
public final class StatsigResourceAttributes {
  static final String ENV_OTEL_SERVICE_NAME = "OTEL_SERVICE_NAME";
  static final String ENV_SERVICE_NAME = "SERVICE_NAME";

  private final String service;
  private final String version;
  private final String environment;

  private StatsigResourceAttributes(String service, String version, String environment) {
    this.service = service;
    this.version = version;
    this.environment = environment;
  }

  /**
   * Returns the attributes for the given options. A null or empty service name falls back to the
   * {@code OTEL_SERVICE_NAME} environment variable, then {@code SERVICE_NAME}.
   */
  public static StatsigResourceAttributes create(
      String serviceName, String version, String environment) {
    return create(serviceName, version, environment, System::getenv);
  }

  static StatsigResourceAttributes create(
      String serviceName, String version, String environment, Function<String, String> getenv) {
    String service =
        firstNonEmpty(
            serviceName, getenv.apply(ENV_OTEL_SERVICE_NAME), getenv.apply(ENV_SERVICE_NAME));
    return new StatsigResourceAttributes(service, emptyToNull(version), emptyToNull(environment));
  }

  /** The service name, or null when there's none. */
  public String getService() {
    return service;
  }

  /** The service version, or null when there's none. */
  public String getVersion() {
    return version;
  }

  /** The environment, or null when there's none. */
  public String getEnvironment() {
    return environment;
  }

  private static String firstNonEmpty(String... values) {
    for (String value : values) {
      if (value != null && !value.isEmpty()) {
        return value;
      }
    }
    return null;
  }

  private static String emptyToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }
}
