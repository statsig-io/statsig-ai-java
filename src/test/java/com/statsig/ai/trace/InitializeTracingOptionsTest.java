package com.statsig.ai.trace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.statsig.ai.trace.options.InitializeTracingOptions;
import com.statsig.ai.trace.options.StatsigOTLPTraceExporterOptions;
import org.junit.Test;

public class InitializeTracingOptionsTest {

  @Test
  public void builderSetsExporterOptions() {
    StatsigOTLPTraceExporterOptions exporterOptions =
        new StatsigOTLPTraceExporterOptions("secret-test-key");

    InitializeTracingOptions options =
        InitializeTracingOptions.builder().setExporterOptions(exporterOptions).build();

    assertSame(exporterOptions, options.getExporterOptions());
    assertEquals("https://api.statsig.com/otlp", options.getExporterOptions().getEndpoint());
    assertEquals("secret-test-key", options.getExporterOptions().getSdkKey());
  }

  @Test
  public void builderConvenienceFactoryInitializesExporterOptions() {
    StatsigOTLPTraceExporterOptions exporterOptions =
        new StatsigOTLPTraceExporterOptions("secret-another", "https://api.fake.com/otlp");

    InitializeTracingOptions options =
        InitializeTracingOptions.builder().setExporterOptions(exporterOptions).build();

    StatsigOTLPTraceExporterOptions actualExporterOptions = options.getExporterOptions();
    assertSame(exporterOptions, actualExporterOptions);
    assertEquals("https://api.fake.com/otlp/v1/traces", actualExporterOptions.getEndpoint());
    assertEquals("secret-another", actualExporterOptions.getSdkKey());
  }
}
